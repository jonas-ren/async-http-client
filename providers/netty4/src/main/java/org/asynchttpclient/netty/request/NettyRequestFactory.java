/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 *     http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.netty.request;

import static org.asynchttpclient.ntlm.NtlmUtils.getNTLM;
import static org.asynchttpclient.util.AsyncHttpProviderUtils.*;
import static org.asynchttpclient.util.AuthenticatorUtils.computeBasicAuthentication;
import static org.asynchttpclient.util.AuthenticatorUtils.computeDigestAuthentication;
import static org.asynchttpclient.util.HttpUtils.isSecure;
import static org.asynchttpclient.util.HttpUtils.isWebSocket;
import static org.asynchttpclient.util.HttpUtils.useProxyConnect;
import static org.asynchttpclient.util.MiscUtils.isNonEmpty;
import static org.asynchttpclient.ws.WebSocketUtils.getKey;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map.Entry;

import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Realm.AuthScheme;
import org.asynchttpclient.Request;
import org.asynchttpclient.cookie.CookieEncoder;
import org.asynchttpclient.netty.request.body.NettyBody;
import org.asynchttpclient.netty.request.body.NettyBodyBody;
import org.asynchttpclient.netty.request.body.NettyByteArrayBody;
import org.asynchttpclient.netty.request.body.NettyByteBufferBody;
import org.asynchttpclient.netty.request.body.NettyCompositeByteArrayBody;
import org.asynchttpclient.netty.request.body.NettyDirectBody;
import org.asynchttpclient.netty.request.body.NettyFileBody;
import org.asynchttpclient.netty.request.body.NettyInputStreamBody;
import org.asynchttpclient.netty.request.body.NettyMultipartBody;
import org.asynchttpclient.ntlm.NtlmEngine;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.request.body.generator.FileBodyGenerator;
import org.asynchttpclient.request.body.generator.InputStreamBodyGenerator;
import org.asynchttpclient.spnego.SpnegoEngine;
import org.asynchttpclient.uri.Uri;
import org.asynchttpclient.util.StringUtils;

public final class NettyRequestFactory {

    public static final String GZIP_DEFLATE = HttpHeaders.Values.GZIP + "," + HttpHeaders.Values.DEFLATE;

    private final AsyncHttpClientConfig config;

    public NettyRequestFactory(AsyncHttpClientConfig config) {
        this.config = config;
    }

    private String requestUri(Uri uri, ProxyServer proxyServer, HttpMethod method) {
        if (method == HttpMethod.CONNECT)
            return getAuthority(uri);

        else if (proxyServer != null && !useProxyConnect(uri))
            return uri.toUrl();

        else {
            String path = getNonEmptyPath(uri);
            if (isNonEmpty(uri.getQuery()))
                return path + "?" + uri.getQuery();
            else
                return path;
        }
    }

    private String hostHeader(Request request, Uri uri) {
        String host = request.getVirtualHost() != null ? request.getVirtualHost() : uri.getHost();
        int port = uri.getPort();
        return port == -1 || port == getDefaultPort(uri) ? host : host + ":" + port;
    }

    public String firstRequestOnlyAuthorizationHeader(Request request, Uri uri, ProxyServer proxyServer, Realm realm) throws IOException {
        String authorizationHeader = null;

        if (realm != null && realm.getUsePreemptiveAuth()) {
            switch (realm.getScheme()) {
            case NTLM:
                String msg = NtlmEngine.INSTANCE.generateType1Msg();
                authorizationHeader = "NTLM " + msg;
                break;
            case KERBEROS:
            case SPNEGO:
                String host;
                if (proxyServer != null)
                    host = proxyServer.getHost();
                else if (request.getVirtualHost() != null)
                    host = request.getVirtualHost();
                else
                    host = uri.getHost();

                try {
                    authorizationHeader = "Negotiate " + SpnegoEngine.instance().generateToken(host);
                } catch (Throwable e) {
                    throw new IOException(e);
                }
                break;
            default:
                break;
            }
        }
        
        return authorizationHeader;
    }
    
    private String systematicAuthorizationHeader(Request request, Uri uri, Realm realm) {

        String authorizationHeader = null;

        if (realm != null && realm.getUsePreemptiveAuth()) {

            switch (realm.getScheme()) {
            case BASIC:
                authorizationHeader = computeBasicAuthentication(realm);
                break;
            case DIGEST:
                if (isNonEmpty(realm.getNonce()))
                    authorizationHeader = computeDigestAuthentication(realm);
                break;
            case NTLM:
            case KERBEROS:
            case SPNEGO:
                // NTLM, KERBEROS and SPNEGO are only set on the first request, see firstRequestOnlyAuthorizationHeader
            case NONE:
                break;
            default:
                throw new IllegalStateException("Invalid Authentication " + realm);
            }
        }

        return authorizationHeader;
    }

    public String firstRequestOnlyProxyAuthorizationHeader(Request request, ProxyServer proxyServer, HttpMethod method) throws IOException {
        String proxyAuthorization = null;

        if (method == HttpMethod.CONNECT) {
            List<String> auth = request.getHeaders().get(HttpHeaders.Names.PROXY_AUTHORIZATION);
            String ntlmHeader = getNTLM(auth);
            if (ntlmHeader != null) {
                proxyAuthorization = ntlmHeader;
            }

        } else if (proxyServer != null && proxyServer.getPrincipal() != null && isNonEmpty(proxyServer.getNtlmDomain())) {
            List<String> auth = request.getHeaders().get(HttpHeaders.Names.PROXY_AUTHORIZATION);
            if (getNTLM(auth) == null) {
                String msg = NtlmEngine.INSTANCE.generateType1Msg();
                proxyAuthorization = "NTLM " + msg;
            }
        }

        return proxyAuthorization;
    }
    
    private String systematicProxyAuthorizationHeader(Request request, ProxyServer proxyServer, Realm realm, HttpMethod method) {

        String proxyAuthorization = null;

        if (method != HttpMethod.CONNECT && proxyServer != null && proxyServer.getPrincipal() != null && proxyServer.getScheme() == AuthScheme.BASIC) {
            proxyAuthorization = computeBasicAuthentication(proxyServer);
        } else if (realm != null && realm.getUsePreemptiveAuth() && realm.isTargetProxy()) {

            switch (realm.getScheme()) {
            case BASIC:
                proxyAuthorization = computeBasicAuthentication(realm);
                break;
            case DIGEST:
                if (isNonEmpty(realm.getNonce()))
                    proxyAuthorization = computeDigestAuthentication(realm);
                break;
            case NTLM:
            case KERBEROS:
            case SPNEGO:
                // NTLM, KERBEROS and SPNEGO are only set on the first request, see firstRequestOnlyAuthorizationHeader
            case NONE:
                break;
            default:
                throw new IllegalStateException("Invalid Authentication " + realm);
            }
        }

        return proxyAuthorization;
    }

    private NettyBody body(Request request, HttpMethod method) throws IOException {
        NettyBody nettyBody = null;
        if (method != HttpMethod.CONNECT) {

            Charset bodyCharset = request.getBodyCharset() == null ? DEFAULT_CHARSET : request.getBodyCharset();

            if (request.getByteData() != null)
                nettyBody = new NettyByteArrayBody(request.getByteData());

            else if (request.getCompositeByteData() != null)
                nettyBody = new NettyCompositeByteArrayBody(request.getCompositeByteData());
                
            else if (request.getStringData() != null)
                nettyBody = new NettyByteBufferBody(StringUtils.charSequence2ByteBuffer(request.getStringData(), bodyCharset));

            else if (request.getStreamData() != null)
                nettyBody = new NettyInputStreamBody(request.getStreamData(), config);

            else if (isNonEmpty(request.getFormParams())) {

                String contentType = null;
                if (!request.getHeaders().containsKey(HttpHeaders.Names.CONTENT_TYPE))
                    contentType = HttpHeaders.Values.APPLICATION_X_WWW_FORM_URLENCODED;

                nettyBody = new NettyByteBufferBody(urlEncodeFormParams(request.getFormParams(), bodyCharset), contentType);

            } else if (isNonEmpty(request.getParts()))
                nettyBody = new NettyMultipartBody(request.getParts(), request.getHeaders(), config);

            else if (request.getFile() != null)
                nettyBody = new NettyFileBody(request.getFile(), config);

            else if (request.getBodyGenerator() instanceof FileBodyGenerator) {
                FileBodyGenerator fileBodyGenerator = (FileBodyGenerator) request.getBodyGenerator();
                nettyBody = new NettyFileBody(fileBodyGenerator.getFile(), fileBodyGenerator.getRegionSeek(), fileBodyGenerator.getRegionLength(), config);

            } else if (request.getBodyGenerator() instanceof InputStreamBodyGenerator)
                nettyBody = new NettyInputStreamBody(InputStreamBodyGenerator.class.cast(request.getBodyGenerator()).getInputStream(), config);

            else if (request.getBodyGenerator() != null)
                nettyBody = new NettyBodyBody(request.getBodyGenerator().createBody(), config);
        }

        return nettyBody;
    }

    public void addAuthorizationHeader(HttpHeaders headers, String authorizationHeader) {
        if (authorizationHeader != null)
            // don't override authorization but append
            headers.add(HttpHeaders.Names.AUTHORIZATION, authorizationHeader);
    }
    
    public void setProxyAuthorizationHeader(HttpHeaders headers, String proxyAuthorizationHeader) {
        if (proxyAuthorizationHeader != null)
            headers.set(HttpHeaders.Names.PROXY_AUTHORIZATION, proxyAuthorizationHeader);
    }

    public NettyRequest newNettyRequest(Request request, Uri uri, boolean forceConnect, ProxyServer proxyServer) throws IOException {

        HttpMethod method = forceConnect ? HttpMethod.CONNECT : HttpMethod.valueOf(request.getMethod());
        HttpVersion httpVersion = method == HttpMethod.CONNECT && proxyServer.isForceHttp10() ? HttpVersion.HTTP_1_0 : HttpVersion.HTTP_1_1;
        String requestUri = requestUri(uri, proxyServer, method);

        NettyBody body = body(request, method);

        HttpRequest httpRequest;
        NettyRequest nettyRequest;
        if (body instanceof NettyDirectBody) {
            ByteBuf buf = NettyDirectBody.class.cast(body).byteBuf();
            httpRequest = new DefaultFullHttpRequest(httpVersion, method, requestUri, buf);
            // body is passed as null as it's written directly with the request
            nettyRequest = new NettyRequest(httpRequest, null);

        } else if (body == null) {
            httpRequest = new DefaultFullHttpRequest(httpVersion, method, requestUri);
            nettyRequest = new NettyRequest(httpRequest, null);

        } else {
            httpRequest = new DefaultHttpRequest(httpVersion, method, requestUri);
            nettyRequest = new NettyRequest(httpRequest, body);
        }

        HttpHeaders headers = httpRequest.headers();

        if (method != HttpMethod.CONNECT) {
            // assign headers as configured on request
            for (Entry<String, List<String>> header : request.getHeaders()) {
                headers.set(header.getKey(), header.getValue());
            }

            if (isNonEmpty(request.getCookies()))
                headers.set(HttpHeaders.Names.COOKIE, CookieEncoder.encode(request.getCookies()));

            if (config.isCompressionEnforced() && !headers.contains(HttpHeaders.Names.ACCEPT_ENCODING))
                headers.set(HttpHeaders.Names.ACCEPT_ENCODING, GZIP_DEFLATE);
        }

        if (body != null) {
            if (body.getContentLength() < 0)
                headers.set(HttpHeaders.Names.TRANSFER_ENCODING, HttpHeaders.Values.CHUNKED);
            else
                headers.set(HttpHeaders.Names.CONTENT_LENGTH, body.getContentLength());

            if (body.getContentType() != null)
                headers.set(HttpHeaders.Names.CONTENT_TYPE, body.getContentType());
        }

        // connection header and friends
        boolean webSocket = isWebSocket(uri.getScheme());
        if (method != HttpMethod.CONNECT && webSocket) {
            headers.set(HttpHeaders.Names.UPGRADE, HttpHeaders.Values.WEBSOCKET)//
            .set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.UPGRADE)//
            .set(HttpHeaders.Names.ORIGIN, "http://" + uri.getHost() + ":" + (uri.getPort() == -1 ? isSecure(uri.getScheme()) ? 443 : 80 : uri.getPort()))//
            .set(HttpHeaders.Names.SEC_WEBSOCKET_KEY, getKey())//
            .set(HttpHeaders.Names.SEC_WEBSOCKET_VERSION, "13");

        } else if (!headers.contains(HttpHeaders.Names.CONNECTION)) {
            headers.set(HttpHeaders.Names.CONNECTION, keepAliveHeaderValue(config));
        }

        if (!headers.contains(HttpHeaders.Names.HOST))
            headers.set(HttpHeaders.Names.HOST,  hostHeader(request, uri));

        Realm realm = request.getRealm() != null ? request.getRealm() : config.getRealm();

        // don't override authorization but append
        addAuthorizationHeader(headers, systematicAuthorizationHeader(request, uri, realm));

        setProxyAuthorizationHeader(headers, systematicProxyAuthorizationHeader(request, proxyServer, realm, method));

        // Add default accept headers
        if (!headers.contains(HttpHeaders.Names.ACCEPT))
            headers.set(HttpHeaders.Names.ACCEPT, "*/*");

        // Add default user agent
        if (!headers.contains(HttpHeaders.Names.USER_AGENT) && config.getUserAgent() != null)
            headers.set(HttpHeaders.Names.USER_AGENT, config.getUserAgent());

        return nettyRequest;
    }
}
