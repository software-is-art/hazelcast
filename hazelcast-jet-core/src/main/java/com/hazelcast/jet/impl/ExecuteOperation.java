/*
 * Copyright (c) 2008-2016, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.impl;

import java.util.concurrent.CompletionStage;

class ExecuteOperation extends AsyncExecutionOperation {

    private volatile CompletionStage<Void> executionFuture;
    private Throwable cachedExceptionResult;

    ExecuteOperation(String engineName, long executionId) {
        super(engineName, executionId);
    }

    private ExecuteOperation() {
        // for deserialization
    }

    @Override
    void cancel() {
        if (executionFuture != null) {
            executionFuture.toCompletableFuture().cancel(true);
        }
    }

    @Override
    void completeExceptionally(Throwable throwable) {
        if (executionFuture == null) {
            this.cachedExceptionResult = throwable;
        } else {
            executionFuture.toCompletableFuture().completeExceptionally(throwable);
        }
    }

    @Override
    protected void doRun() throws Exception {
        JetService service = getService();
        EngineContext engineContext = service.getEngineContext(engineName);
        executionFuture = engineContext.getExecutionContext(executionId)
                                       .execute(f -> f.handle((r, error) -> error != null ? error : null)
                                                      .thenAccept(this::doSendResponse));
        if (cachedExceptionResult != null) {
            executionFuture.toCompletableFuture().completeExceptionally(cachedExceptionResult);
        }
    }
}
