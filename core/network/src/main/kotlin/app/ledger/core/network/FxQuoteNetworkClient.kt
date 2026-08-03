package app.ledger.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

public data class NetworkFxQuoteRequest(
    val sourceCode: String,
    val targetCode: String,
    val date: LocalDate?,
) {
    init {
        require(CURRENCY.matches(sourceCode))
        require(CURRENCY.matches(targetCode))
        require(sourceCode != targetCode)
    }

    private companion object {
        val CURRENCY = Regex("[A-Z]{3}")
    }
}

public data class NetworkFxQuote(
    val sourceCode: String,
    val targetCode: String,
    val rate: BigDecimal,
    val provider: String,
    val quotedDate: LocalDate,
    val fetchedAt: Instant,
)

public sealed interface NetworkFxQuoteResult {
    public data class Available(val quote: NetworkFxQuote) : NetworkFxQuoteResult
    public data object Unavailable : NetworkFxQuoteResult
    public data object InvalidResponse : NetworkFxQuoteResult
}

public fun interface NetworkInstantSource {
    public fun now(): Instant
}

public fun interface FxQuoteNetworkPort {
    public suspend fun quote(request: NetworkFxQuoteRequest): NetworkFxQuoteResult
}

/**
 * Cancellable, bounded-retry Frankfurter adapter. Requests contain only an ISO currency pair and an
 * optional date; amounts and ledger identifiers never cross this boundary.
 */
public class OkHttpFxQuoteNetworkClient(
    private val client: OkHttpClient,
    private val endpoint: HttpUrl,
    private val instantSource: NetworkInstantSource,
    private val maxRetries: Int = 1,
) : FxQuoteNetworkPort {
    init {
        require(endpoint.isHttps || endpoint.host in LOOPBACK_HOSTS)
        require(maxRetries in 0..2)
    }

    override suspend fun quote(request: NetworkFxQuoteRequest): NetworkFxQuoteResult = withContext(Dispatchers.IO) {
        val path = request.date?.toString() ?: "latest"
        val url = endpoint.newBuilder()
            .addPathSegment("v1")
            .addPathSegment(path)
            .addQueryParameter("base", request.sourceCode)
            .addQueryParameter("symbols", request.targetCode)
            .build()
        val httpRequest = Request.Builder().url(url).header("Accept", "application/json").get().build()
        var attempt = 0
        while (true) {
            coroutineContext.ensureActive()
            val call = client.newCall(httpRequest)
            val completion = coroutineContext.job.invokeOnCompletion { cause -> if (cause != null) call.cancel() }
            try {
                call.execute().use { response ->
                    if (response.isSuccessful) {
                        return@withContext parse(response.body.string(), request, instantSource.now())
                    }
                    if (response.code !in RETRYABLE_CODES || attempt >= maxRetries) {
                        return@withContext NetworkFxQuoteResult.Unavailable
                    }
                }
            } catch (_: IOException) {
                if (attempt >= maxRetries) return@withContext NetworkFxQuoteResult.Unavailable
            } finally {
                completion.dispose()
            }
            attempt += 1
        }
        @Suppress("UNREACHABLE_CODE")
        NetworkFxQuoteResult.Unavailable
    }

    private fun parse(body: String, request: NetworkFxQuoteRequest, fetchedAt: Instant): NetworkFxQuoteResult = try {
        val root = JSON.parseToJsonElement(body).jsonObject
        val base = root.getValue("base").jsonPrimitive.content
        val date = LocalDate.parse(root.getValue("date").jsonPrimitive.content)
        val rate = root.getValue("rates").jsonObject.getValue(request.targetCode).jsonPrimitive.content.toBigDecimal()
        if (base != request.sourceCode || rate.signum() <= 0) {
            NetworkFxQuoteResult.InvalidResponse
        } else {
            NetworkFxQuoteResult.Available(
                NetworkFxQuote(base, request.targetCode, rate.stripTrailingZeros(), PROVIDER, date, fetchedAt),
            )
        }
    } catch (_: Exception) {
        NetworkFxQuoteResult.InvalidResponse
    }

    public companion object {
        public val PRODUCTION_ENDPOINT: HttpUrl = HttpUrl.Builder()
            .scheme("https")
            .host("api.frankfurter.dev")
            .build()

        /** Frozen production policy: bounded calls, no transparent OkHttp retry, one explicit retry. */
        public fun production(instantSource: NetworkInstantSource): FxQuoteNetworkPort = OkHttpFxQuoteNetworkClient(
            client = OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build(),
            endpoint = PRODUCTION_ENDPOINT,
            instantSource = instantSource,
            maxRetries = EXPLICIT_RETRIES,
        )

        private const val CONNECT_TIMEOUT_SECONDS = 5L
        private const val READ_TIMEOUT_SECONDS = 10L
        private const val CALL_TIMEOUT_SECONDS = 15L
        private const val EXPLICIT_RETRIES = 1
        private const val PROVIDER = "frankfurter"
        private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1")
        private val RETRYABLE_CODES = setOf(408, 425, 429, 500, 502, 503, 504)
        private val JSON = Json { ignoreUnknownKeys = false }
    }
}
