;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns
  ^{:skip-wiki true}
  aleph.http.server
  (:use
    [aleph netty formats]
    [aleph.http utils core]
    [aleph.core channel pipeline]
    [clojure.pprint])
  (:import
    [org.jboss.netty.handler.codec.http
     HttpRequest
     HttpResponse
     HttpMessage
     HttpMethod
     HttpHeaders
     HttpHeaders$Names
     HttpChunk
     DefaultHttpChunk
     DefaultHttpResponse
     HttpVersion
     HttpResponseStatus
     HttpRequestDecoder
     HttpResponseEncoder
     HttpContentCompressor]
    [org.jboss.netty.handler.codec.http.websocket
     WebSocketFrame
     WebSocketFrameDecoder
     WebSocketFrameEncoder]
    [org.jboss.netty.channel
     Channel
     Channels
     ChannelPipeline
     ChannelUpstreamHandler
     ChannelFuture
     MessageEvent
     ExceptionEvent
     ChannelFutureListener
     ChannelFutureProgressListener
     Channels
     DefaultFileRegion]
    [org.jboss.netty.buffer
     ChannelBuffer
     ChannelBufferInputStream
     ChannelBuffers]
    [java.io
     ByteArrayInputStream
     InputStream
     File
     FileInputStream]
    [java.net
     URLConnection]))

;;;

(defn netty-request-method
  "Get HTTP method from Netty request."
  [^HttpRequest req]
  {:request-method (->> req .getMethod .getName .toLowerCase keyword)})

(defn netty-request-uri
  "Get URI from Netty request."
  [^HttpRequest req]
  (let [paths (.split (.getUri req) "[?]")]
    {:uri (first paths)
     :query-string (second paths)}))

(defn transform-netty-request
  "Transforms a Netty request into a Ring request."
  [^HttpRequest req]
  (let [headers (netty-headers req)
	parts (.split (headers "host") "[:]")
	host (first parts)
	port (when-let [port (second parts)]
	       (Integer/parseInt port))]
    (merge
      (netty-request-method req)
      {:headers headers}
      {:body (let [body (transform-netty-body (.getContent req) headers)]
	       (if (final-netty-message? req)
		 body
		 (let [ch (channel)]
		   (when body
		     (enqueue ch body))
		   ch)))}
      {:keep-alive? (HttpHeaders/isKeepAlive req)
       :server-name host
       :server-port port}
      (netty-headers req)
      (netty-request-uri req))))

;;;

(defn transform-aleph-response
  "Turns a Ring response into something Netty can understand."
  [response options]
  (let [response (wrap-response response)
	rsp (DefaultHttpResponse.
	      HttpVersion/HTTP_1_1
	      (HttpResponseStatus/valueOf (:status response)))
	body (:body response)]
    (doseq [[k v-or-vals] (:headers response)]
      (when-not (nil? v-or-vals)
	(if (string? v-or-vals)
	  (.addHeader rsp (to-str k) v-or-vals)
	  (doseq [val v-or-vals]
	    (.addHeader rsp (to-str k) val)))))
    (when body
      (.setContent rsp body))
    (HttpHeaders/setContentLength rsp (-> rsp .getContent .readableBytes))
    (when (:keep-alive? options)
      (.setHeader rsp "connection" "keep-alive"))
    rsp))

(defn respond-with-string
  ([^Channel channel response options]
     (respond-with-string channel response options "UTF-8"))
  ([^Channel channel response options ^String charset]
     (let [body (ChannelBuffers/copiedBuffer
		  ^CharSequence (:body response)
		  (java.nio.charset.Charset/forName charset))
	   response (transform-aleph-response
		      (-> response
			(update-in [:headers] assoc "charset" charset)
			(assoc :body body))
		      options)]
       (-> channel
	 (.write response)
	 (.addListener (response-listener options))))))

(defn respond-with-sequence
  ([channel response options]
     (respond-with-sequence channel response options "UTF-8"))
  ([channel response options charset]
     (respond-with-string channel (update-in response [:body] #(apply str %)) options charset)))

(defn respond-with-stream
  [^Channel channel response options]
  (let [response (transform-aleph-response
		   (update-in response [:body] #(input-stream->channel-buffer %))
		   options)]
    (-> channel
      (.write response)
      (.addListener (response-listener options)))))

;;TODO: use a more efficient file serving mechanism
(defn respond-with-file
  [channel response options]
  (let [file ^File (:body response)
	content-type (or (URLConnection/guessContentTypeFromName (.getName file)) "application/octet-stream")]
    (respond-with-stream
      channel
      (-> response
	(update-in [:headers "content-type"] #(or % content-type))
	(update-in [:body] #(FileInputStream. ^File %)))
      options)))

(defn- respond [channel response options]
  (try
    (let [body (:body response)]
      (cond
       (string? body) (respond-with-string channel response options)
       (sequential? body) (respond-with-sequence channel response options)
       (instance? InputStream body) (respond-with-stream channel response options)
       (instance? File body) (respond-with-file channel response options)))
    (catch Exception e
      (.printStackTrace e))))

;;;

(defn respond-to-handshake [ctx ^HttpRequest request]
  (let [pipeline (-> ctx .getChannel .getPipeline)]
    (.replace pipeline "decoder" "websocket-decoder" (WebSocketFrameDecoder.))
    (-> ctx .getChannel (.write (websocket-response request)))
    (.replace pipeline "encoder" "websocket-encoder" (WebSocketFrameEncoder.))))

(defn websocket-handshake-handler [handler options]
  (let [[inner outer] (channel-pair)]
    (reify ChannelUpstreamHandler
      (handleUpstream [_ ctx evt]
	(if-let [msg (message-event evt)]
	  (cond
	    (instance? WebSocketFrame msg)
	    (enqueue outer (from-websocket-frame msg))
	    (instance? HttpRequest msg)
	    (if (websocket-handshake? msg)
	      (let [ch (.getChannel ctx)]
		(receive-all outer
		  (fn [msg]
		    (when msg
		      (.write ch (to-websocket-frame msg)))
		    (when (closed? outer)
		      (.close ch))))
		(respond-to-handshake ctx msg)
		(handler inner {:websocket true}))
	      (.sendUpstream ctx evt)))
	  (if-let [ch (channel-event evt)]
	    (when-not (.isConnected ch)
	      (when-not (sealed? outer)
		(enqueue-and-close outer nil)))
	    (.sendUpstream ctx evt)))))))

;;;

(defn http-session-handler [handler options]
  (let [handler-channel (atom nil)
	reset-channels (fn [connection-options netty-channel]
			 (let [[outer inner] (channel-pair)
			       keep-alive? (:keep-alive? connection-options)]
			   ;; Aleph -> Netty
			   (receive-all outer
			     (fn [response]  
			       (respond netty-channel
				 response
				 (merge
				   options
				   connection-options
				   {:close? (and (not keep-alive?) (closed? outer))}))))
			   ;; Netty -> Aleph
			   (receive inner
			     (fn [request]
			       (handler inner request)))
			   (reset! handler-channel outer)))]
    (message-stage
      (fn [^Channel netty-channel ^HttpRequest request]
	(try
	  ;; if this is a new request, create a new pair of channels
	  (let [ch @handler-channel]
	    (when (or (not ch) (sealed? ch))
	      (reset-channels
		{:keep-alive? (.isKeepAlive request)}
		netty-channel)))
	  ;; prime handler channel
	  (enqueue-and-close @handler-channel
	    (assoc (transform-netty-request request)
	      :scheme :http
	      :remote-addr (->> netty-channel
			     .getRemoteAddress
			     .getAddress
			     .getHostAddress)))
	  (catch Exception e
	    (.printStackTrace e)))))))

(defn create-pipeline
  "Creates an HTTP pipeline."
  [handler options]
  (let [pipeline ^ChannelPipeline
	(create-netty-pipeline
	  :decoder (HttpRequestDecoder.)
	  :encoder (HttpResponseEncoder.)
	  :deflater (HttpContentCompressor.)
	  :upstream-error (upstream-stage error-stage-handler)
	  :http-request (http-session-handler handler options)
	  :downstream-error (downstream-stage error-stage-handler))]
    (when (:websocket options)
      (.addBefore pipeline "http-request" "websocket" (websocket-handshake-handler handler options)))
    pipeline))

(defn start-http-server
  "Starts an HTTP server."
  [handler options]
  (start-server
    #(create-pipeline handler options)
    (assoc options
      :error-handler (fn [^Throwable e] (.printStackTrace e)))))



