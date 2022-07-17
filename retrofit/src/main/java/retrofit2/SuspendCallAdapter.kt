/*
 * Copyright (C) 2022 Square, Inc.
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
package retrofit2

import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

/**
 * Adapts a {@link Call} with response type {@code R} into the type of {@code T} with coroutine scope to do async work.
 * Instances are created by {@linkplain Factory a factory} which is {@linkplain
 * Retrofit.Builder#addSuspendCallAdapterFactory(Factory) installed} into the {@link Retrofit} instance.
 */
interface SuspendCallAdapter<R, T> {

  /**
   * Returns the value type that this adapter uses when converting the HTTP response body to a Java
   * object. For example, the response type for {@code Call<Repo>} is {@code Repo}. This type is
   * used to prepare the {@code call} passed to {@code #adapt}.
   *
   * <p>Note: This is typically not the same type as the {@code returnType} provided to this call
   * adapter's factory.
   */
  fun responseType(): Type

  /**
   * Returns an instance of {@code T} which delegates to {@code call}.
   */
  suspend fun adapt(call: Call<R>): T

  /**
   * Creates [CallAdapter] instances based on the return type of [ ][Retrofit.create] methods.
   */
  abstract class Factory {
    /**
     * Returns a call adapter for interface methods that return `returnType`, or null if it
     * cannot be handled by this factory.
     */
    abstract operator fun get(
      returnType: Type, annotations: Array<Annotation>, retrofit: Retrofit
    ): SuspendCallAdapter<*, *>?

    companion object {
      /**
       * Extract the upper bound of the generic parameter at `index` from `type`. For
       * example, index 1 of `Map<String, ? extends Runnable>` returns `Runnable`.
       */
      @JvmStatic
      protected fun getParameterUpperBound(index: Int, type: ParameterizedType): Type {
        return Utils.getParameterUpperBound(index, type)
      }

      /**
       * Extract the raw class type from `type`. For example, the type representing `List<? extends Runnable>` returns `List.class`.
       */
      @JvmStatic
      protected fun getRawType(type: Type): Class<*> {
        return Utils.getRawType(type)
      }
    }
  }
}
