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

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST
import okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE
import org.assertj.core.api.Assertions.assertThat
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import retrofit2.helpers.ToStringConverterFactory
import retrofit2.http.GET
import retrofit2.http.HEAD
import java.io.IOException
import java.lang.Runnable
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.*

class KotlinDirectSuspendTest {
  @get:Rule
  val server = MockWebServer()

  interface Service {
    @GET("/")
    suspend fun body(): String

    @GET("/")
    suspend fun unit()

    @HEAD("/")
    suspend fun headUnit()
  }

  @Test
  fun body() {
    val retrofit = Retrofit.Builder()
      .baseUrl(server.url("/"))
      .addSuspendCallAdapterFactory(DirectSuspendCallAdapter.SuspendCallAdapterFactory())
      .addConverterFactory(ToStringConverterFactory())
      .build()
    val example = retrofit.create(Service::class.java)

    server.enqueue(MockResponse().setBody("Hi"))

    val body = runBlocking { example.body() }
    assertThat(body).isEqualTo("Hi")
  }

  @Test
  fun body404() {
    val retrofit = Retrofit.Builder()
      .baseUrl(server.url("/"))
      .addSuspendCallAdapterFactory(DirectSuspendCallAdapter.SuspendCallAdapterFactory())
      .addConverterFactory(ToStringConverterFactory())
      .build()
    val example = retrofit.create(Service::class.java)

    server.enqueue(MockResponse().setResponseCode(404))

    try {
      runBlocking { example.body() }
      fail()
    } catch (e: HttpException) {
      assertThat(e.code()).isEqualTo(404)
    }
  }

  @Test
  fun bodyFailure() {
    val retrofit = Retrofit.Builder()
      .baseUrl(server.url("/"))
      .addSuspendCallAdapterFactory(DirectSuspendCallAdapter.SuspendCallAdapterFactory())
      .addConverterFactory(ToStringConverterFactory())
      .build()
    val example = retrofit.create(Service::class.java)

    server.enqueue(MockResponse().setSocketPolicy(DISCONNECT_AFTER_REQUEST))

    try {
      runBlocking { example.body() }
      fail()
    } catch (e: IOException) {
    }
  }

  @Test
  fun bodyThrowsOnNull() {
    val retrofit = Retrofit.Builder()
      .baseUrl(server.url("/"))
      .addSuspendCallAdapterFactory(DirectSuspendCallAdapter.SuspendCallAdapterFactory())
      .addConverterFactory(ToStringConverterFactory())
      .build()
    val example = retrofit.create(Service::class.java)

    server.enqueue(MockResponse().setResponseCode(204))

    try {
      runBlocking { example.body() }
      fail()
    } catch (e: KotlinNullPointerException) {
      // Coroutines wraps exceptions with a synthetic trace so fall back to cause message.
      val message = e.message ?: (e.cause as KotlinNullPointerException).message
      assertThat(message).isEqualTo(
        "Response from retrofit2.KotlinDirectSuspendTest\$Service.body was null but response body type was declared as non-null"
      )
    }
  }

  @Test
  fun unit() {
    val retrofit = Retrofit.Builder()
      .baseUrl(server.url("/"))
      .addSuspendCallAdapterFactory(DirectSuspendCallAdapter.SuspendCallAdapterFactory())
      .build()
    val example = retrofit.create(Service::class.java)
    server.enqueue(MockResponse().setBody("Unit"))
    runBlocking { example.unit() }
  }

  // TODO: fix this test
  @Test
  fun unitNullableBody() {
    val retrofit = Retrofit.Builder()
      .baseUrl(server.url("/"))
      .addSuspendCallAdapterFactory(DirectSuspendCallAdapter.SuspendCallAdapterFactory())
      .build()
    val example = retrofit.create(Service::class.java)
    server.enqueue(MockResponse().setResponseCode(204))
    runBlocking { example.unit() }
  }

  @Test
  fun headUnit() {
    val retrofit = Retrofit.Builder()
      .addSuspendCallAdapterFactory(DirectSuspendCallAdapter.SuspendCallAdapterFactory())
      .baseUrl(server.url("/"))
      .build()
    val example = retrofit.create(Service::class.java)
    server.enqueue(MockResponse())
    runBlocking { example.headUnit() }
  }

  @Test
  fun cancelationWorks() {
    lateinit var call: okhttp3.Call
    lateinit var innerDeferred: Deferred<Unit>

    val callAdapterFactory = object : SuspendCallAdapter.Factory() {
      override fun get(returnType: Type, annotations: Array<Annotation>, retrofit: Retrofit): SuspendCallAdapter<*, *>? {
        if (returnType !is ParameterizedType || returnType.rawType != Call::class.java) {
          return null
        }

        val resultType = getParameterUpperBound(0, returnType)
        return object : SuspendCallAdapter<String, String> {
          override fun responseType(): Type = resultType

          override suspend fun adapt(call: Call<String>): String {
            innerDeferred = CompletableDeferred(coroutineContext.job)
            return call.await()
          }
        }
      }
    }


    val okHttpClient = OkHttpClient()
    val retrofit = Retrofit.Builder()
      .baseUrl(server.url("/"))
      .addSuspendCallAdapterFactory(callAdapterFactory)
      .callFactory {
        val newCall = okHttpClient.newCall(it)
        call = newCall
        newCall
      }
      .addConverterFactory(ToStringConverterFactory())
      .build()
    val example = retrofit.create(Service::class.java)

    // This leaves the connection open indefinitely allowing us to cancel without racing a body.
    server.enqueue(MockResponse().setSocketPolicy(NO_RESPONSE))

    val deferred = GlobalScope.async { example.body() }

    // This will block until the server has received the request ensuring it's in flight.
    server.takeRequest()

    deferred.cancel()
    assertTrue(call.isCanceled)
    assertTrue(innerDeferred.isCancelled)
  }

  @Test
  fun checkedExceptionsAreNotSynchronouslyThrownForBody() = runBlocking {
    val retrofit = Retrofit.Builder()
      .baseUrl("https://unresolved-host.com/")
      .addSuspendCallAdapterFactory(DirectSuspendCallAdapter.SuspendCallAdapterFactory())
      .addConverterFactory(ToStringConverterFactory())
      .build()
    val example = retrofit.create(Service::class.java)

    server.shutdown()

    // Run with a dispatcher that prevents yield from actually deferring work. An old workaround
    // for this problem relied on yield, but it is not guaranteed to prevent direct execution.
    withContext(DirectUnconfinedDispatcher) {
      // The problematic behavior of the UnknownHostException being synchronously thrown is
      // probabilistic based on thread preemption. Running a thousand times will almost always
      // trigger it, so we run an order of magnitude more to be safe.
      repeat(10000) {
        try {
          example.body()
          fail()
        } catch (_: IOException) {
          // We expect IOException, the bad behavior will wrap this in UndeclaredThrowableException.
        }
      }
    }
  }

  private class DirectSuspendCallAdapter<R : Any>(private val resultType: Type) :
    SuspendCallAdapter<R, R> {

    override fun responseType(): Type = resultType

    override suspend fun adapt(call: Call<R>): R = call.await()

    class SuspendCallAdapterFactory : SuspendCallAdapter.Factory() {
      override fun get(returnType: Type, annotations: Array<Annotation>, retrofit: Retrofit): SuspendCallAdapter<out Any, *>? {
        if (returnType !is ParameterizedType || returnType.rawType != Call::class.java) {
          return null
        }

        val resultType = getParameterUpperBound(0, returnType)
        if (resultType == Unit::class.java) return null

        return DirectSuspendCallAdapter(resultType)
      }
    }
  }

  @Suppress("EXPERIMENTAL_OVERRIDE")
  private object DirectUnconfinedDispatcher : CoroutineDispatcher() {
    override fun isDispatchNeeded(context: CoroutineContext): Boolean = false
    override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
  }
}
