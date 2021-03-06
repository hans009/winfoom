/*
 *  Copyright (c) 2020. Eugen Covaci
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *   http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and limitations under the License.
 */

package org.kpax.winfoom.proxy.processor;

import org.apache.http.*;
import org.apache.http.impl.execchain.*;
import org.kpax.winfoom.annotation.*;
import org.kpax.winfoom.config.*;
import org.kpax.winfoom.exception.*;
import org.kpax.winfoom.proxy.*;
import org.kpax.winfoom.util.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.*;

import java.io.*;
import java.net.*;

/**
 * Process a CONNECT request through a HTTP proxy.
 *
 * @author Eugen Covaci {@literal eugen.covaci.q@gmail.com}
 * Created on 4/13/2020
 */
@ThreadSafe
@Component
class HttpConnectClientConnectionProcessor extends ClientConnectionProcessor {

    private final Logger logger = LoggerFactory.getLogger(HttpConnectClientConnectionProcessor.class);

    @Autowired
    private TunnelConnection tunnelConnection;

    @Autowired
    private ProxyConfig proxyConfig;

    @Override
    void handleRequest(final ClientConnection clientConnection, final ProxyInfo proxyInfo)
            throws IOException, HttpException, ProxyAuthorizationException {
        RequestLine requestLine = clientConnection.getRequestLine();
        HttpHost target = HttpHost.create(requestLine.getUri());
        HttpHost proxy = new HttpHost(proxyInfo.getProxyHost().getHostName(), proxyInfo.getProxyHost().getPort());
        try (Tunnel tunnel = tunnelConnection.open(proxy, target, requestLine.getProtocolVersion())) {
            try {
                // Handle the tunnel response
                logger.debug("Write status line {}", tunnel.getStatusLine());
                clientConnection.write(tunnel.getStatusLine());

                for (Header header : tunnel.getResponse().getAllHeaders()) {
                    logger.debug("Write header {}", header);
                    clientConnection.write(header);
                }

                // Write empty line
                clientConnection.writeln();

                // The proxy facade mediates the full duplex communication
                // between the client and the remote proxy.
                // This usually ends on connection reset, timeout or any other error
                duplex(tunnel, clientConnection);
            } catch (Exception e) {
                logger.debug("Error on handling CONNECT response", e);
            }
        } catch (TunnelRefusedException tre) {
            logger.debug("The tunnel request was rejected by the proxy host", tre);
            if (tre.getResponse().getStatusLine().getStatusCode() == HttpStatus.SC_PROXY_AUTHENTICATION_REQUIRED &&
                    proxyConfig.isKerberos()) {
                throw new ProxyAuthorizationException(tre.getResponse());
            }
            try {
                clientConnection.writeHttpResponse(tre.getResponse());
            } catch (Exception e) {
                logger.debug("Error on writing response", e);
            }
        }
    }

    @Override
    void handleError(ClientConnection clientConnection, ProxyInfo proxyInfo, Exception e)
            throws ProxyConnectException {
        if (e instanceof ConnectException) {
            if (HttpUtils.isConnectionTimeout((ConnectException) e)
                    || HttpUtils.isConnectionRefused((ConnectException) e)) {
                throw new ProxyConnectException(e.getMessage(), e);
            } else {
                clientConnection.writeErrorResponse(HttpStatus.SC_GATEWAY_TIMEOUT, e.getMessage());
            }
        } else if (e instanceof UnknownHostException) {
            throw new ProxyConnectException(e.getMessage(), e);
        } else if (e instanceof NoHttpResponseException) {
            clientConnection.writeErrorResponse(HttpStatus.SC_GATEWAY_TIMEOUT, e.getMessage());
        } else {
            // Generic error
            clientConnection.writeErrorResponse(HttpStatus.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }
}
