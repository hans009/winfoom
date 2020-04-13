/*
 * Copyright (c) 2020. Eugen Covaci
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package org.kpax.winfoom.proxy;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.RequestLine;
import org.apache.http.impl.execchain.TunnelRefusedException;
import org.kpax.winfoom.config.UserConfig;
import org.kpax.winfoom.util.HttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * @author Eugen Covaci {@literal eugen.covaci.q@gmail.com}
 * Created on 4/13/2020
 */
@Component
class ConnectRequestHandler {

    private final Logger logger = LoggerFactory.getLogger(ConnectRequestHandler.class);

    @Autowired
    private UserConfig userConfig;

    @Autowired
    private ProxyContext proxyContext;

    @Autowired
    private CustomProxyClient proxyClient;

    /**
     * Creates a tunnel through proxy, then let the client and the remote proxy
     * communicate via the local socket channel instance.
     *
     * @param requestLine The first line of the request.
     * @throws HttpException
     * @throws IOException
     */
    void handleConnect(final RequestLine requestLine,
                       AsynchronousSocketChannelWrapper localSocketChannel)
            throws HttpException, IOException {
        logger.debug("Handle proxy connect request");
        Pair<String, Integer> hostPort = HttpUtils.parseConnectUri(requestLine.getUri());
        HttpHost proxy = new HttpHost(userConfig.getProxyHost(), userConfig.getProxyPort());
        HttpHost target = new HttpHost(hostPort.getLeft(), hostPort.getRight());

        try (Tunnel tunnel = proxyClient.tunnel(proxy, target, requestLine.getProtocolVersion())) {
            try {
                handleResponse(tunnel, localSocketChannel);
            } catch (Exception e) {
                logger.debug("Error on handling CONNECT response", e);
            }
        } catch (TunnelRefusedException tre) {
            logger.debug("The tunnel request was rejected by the proxy host", tre);
            try {
                localSocketChannel.writeHttpResponse(tre.getResponse());
            } catch (Exception e) {
                logger.debug("Error on writing response", e);
            }
        }

    }

    /**
     * Handles the tunnel's response.<br>
     *
     * @param tunnel The tunnel's instance
     * @throws IOException
     */
    private void handleResponse(final Tunnel tunnel,
                                AsynchronousSocketChannelWrapper localSocketChannel) throws IOException {
        logger.debug("Write status line");
        localSocketChannel.write(tunnel.getStatusLine());

        logger.debug("Write empty line");
        localSocketChannel.writeln();

        logger.debug("Start full duplex communication");
        Socket socket = tunnel.getSocket();
        final InputStream socketInputStream = socket.getInputStream();
        Future<?> localToSocket = proxyContext.executeAsync(
                () -> socketInputStream.transferTo(localSocketChannel.getOutputStream()));
        try {
            localSocketChannel.getInputStream().transferTo(socket.getOutputStream());
            if (!localToSocket.isDone()) {

                // Wait for async copy to finish
                try {
                    localToSocket.get();
                } catch (ExecutionException e) {
                    logger.debug("Error on writing to socket", e);
                } catch (Exception e) {
                    logger.debug("Failed to write to socket", e);
                }
            }
        } catch (IOException e) {
            localToSocket.cancel(true);
            logger.debug("Error on reading from socket", e);
        }

    }

}