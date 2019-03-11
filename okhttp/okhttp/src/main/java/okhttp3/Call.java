/*
 * Copyright (C) 2014 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3;

import java.io.IOException;
import okio.Timeout;

/**
 * A call is a request that has been prepared for execution. A call can be canceled. As this object
 * represents a single request/response pair (stream), it cannot be executed twice.
 */
public interface Call extends Cloneable {
  // 返回当前请求
  Request request();

  // 同步请求方法
  Response execute() throws IOException;

  // 异步请求方法
  void enqueue(Callback responseCallback);

  // 取消请求
  void cancel();

  // 请求是否在执行（当execute()或者enqueue(Callback responseCallback)执行后该方法返回true）
  boolean isExecuted();

  // 请求是否被取消
  boolean isCanceled();

  Timeout timeout();

  // 创建一个新的一模一样的请求
  Call clone();

  interface Factory {
    Call newCall(Request request);
  }
}
