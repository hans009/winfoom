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

package org.kpax.winfoom.proxy.client;

import org.apache.http.RequestLine;
import org.kpax.winfoom.config.UserConfig;
import org.kpax.winfoom.proxy.ProxyInfo;
import org.kpax.winfoom.util.HttpUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * @author Eugen Covaci {@literal eugen.covaci.q@gmail.com}
 * Created on 4/13/2020
 */
@Component
class DefaultClientProcessorSelector implements ClientProcessorSelector {

    @Autowired
    private UserConfig userConfig;

    @Autowired
    private HttpConnectClientConnectionProcessor httpConnectClientConnectionProcessor;

    @Autowired
    private SocketConnectClientConnectionProcessor socketConnectClientConnectionProcessor;

    @Autowired
    private NonConnectClientConnectionProcessor nonConnectClientConnectionProcessor;

    @Override
    public ClientConnectionProcessor selectClientProcessor(RequestLine requestLine, ProxyInfo proxyInfo) {
        if (HttpUtils.HTTP_CONNECT.equalsIgnoreCase(requestLine.getMethod())) {
            if (proxyInfo.getType().isSocks() || proxyInfo.getType().isDirect()) {
                return socketConnectClientConnectionProcessor;
            }
            return httpConnectClientConnectionProcessor;
        } else {
            return nonConnectClientConnectionProcessor;
        }
    }
}