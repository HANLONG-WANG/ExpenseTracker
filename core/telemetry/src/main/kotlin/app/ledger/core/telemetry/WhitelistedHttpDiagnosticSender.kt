package app.ledger.core.telemetry

import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** HTTPS client whose two payload shapes are closed at compile time. */
@Suppress("TooManyFunctions")
public class WhitelistedHttpDiagnosticSender private constructor(
    private val endpoint: HttpUrl,
    private val client: OkHttpClient,
    private val appVersion: String,
    private val androidMajor: Int,
    private val deviceCategory: DeviceCapabilityCategory,
    allowCleartextForTest: Boolean,
) : WhitelistedDiagnosticSender {
    init {
        require(endpoint.isHttps || allowCleartextForTest)
        require(APP_VERSION.matches(appVersion))
        require(androidMajor in MINIMUM_ANDROID_MAJOR..MAXIMUM_ANDROID_MAJOR)
    }

    override fun sendFeature(identifier: String, entry: FeatureQueueEntry): SendDisposition = send(
        encodeFeature(identifier, entry),
    )

    override fun sendCrash(identifier: String, entry: CrashQueueEntry): SendDisposition {
        if (!DiagnosticUploadStringScanner.isSafe(entry.diagnostic)) return SendDisposition.DROPPED_BY_POLICY
        return send(encodeCrash(identifier, entry))
    }

    private fun send(payload: String): SendDisposition = try {
        client.newCall(
            Request.Builder()
                .url(endpoint)
                .post(payload.toRequestBody(JSON))
                .header("Accept", "application/json")
                .build(),
        ).execute().use { response ->
            if (response.isSuccessful) SendDisposition.SENT else SendDisposition.RETRY
        }
    } catch (_: IOException) {
        SendDisposition.RETRY
    }

    internal fun encodeFeature(identifier: String, entry: FeatureQueueEntry): String = buildString {
        append('{')
        fixed("schema", "ledger-feature-v1")
        comma()
        fixed("install_id", requireIdentifier(identifier))
        comma()
        runtimeMetadata()
        comma()
        number("occurred_at", entry.occurredAtEpochMillis)
        comma()
        fixed("event", entry.event.name.name)
        comma()
        fixed("entry", entry.event.entry.name)
        comma()
        fixed("result", entry.event.outcome.name)
        comma()
        fixed("duration", entry.event.duration.name)
        comma()
        fixed("error_code", entry.event.errorCode.name)
        append('}')
    }

    internal fun encodeCrash(identifier: String, entry: CrashQueueEntry): String = buildString {
        append('{')
        fixed("schema", "ledger-crash-v1")
        comma()
        fixed("install_id", requireIdentifier(identifier))
        comma()
        runtimeMetadata()
        comma()
        number("occurred_at", entry.occurredAtEpochMillis)
        comma()
        fixed("kind", entry.diagnostic.kind.name)
        comma()
        fixed("error_code", entry.diagnostic.errorCode.name)
        comma()
        append("\"frames\":[")
        entry.diagnostic.frames.forEachIndexed { index, frame ->
            if (index > 0) comma()
            append('{')
            fixed("class", frame.className)
            comma()
            fixed("method", frame.methodName)
            comma()
            number("line", frame.lineNumber.toLong())
            append('}')
        }
        append("]}")
    }

    private fun StringBuilder.fixed(name: String, value: String) {
        append('"').append(name).append("\":\"").append(escape(value)).append('"')
    }

    private fun StringBuilder.number(name: String, value: Long) {
        append('"').append(name).append("\":").append(value)
    }

    private fun StringBuilder.comma() {
        append(',')
    }

    private fun StringBuilder.runtimeMetadata() {
        fixed("app_version", appVersion)
        comma()
        number("android_major", androidMajor.toLong())
        comma()
        fixed("device_capability", deviceCategory.name)
    }

    private fun escape(value: String): String = buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                in '\u0020'..'\u007e' -> append(character)
                else -> append('?')
            }
        }
    }

    private fun requireIdentifier(value: String): String {
        require(INSTALL_IDENTIFIER.matches(value))
        return value
    }

    public companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val INSTALL_IDENTIFIER = Regex("[0-9a-f]{32}")
        private val APP_VERSION = Regex("[A-Za-z0-9._+-]{1,64}")
        private const val MINIMUM_ANDROID_MAJOR = 1
        private const val MAXIMUM_ANDROID_MAJOR = 100

        public fun production(
            endpoint: HttpUrl,
            client: OkHttpClient,
            appVersion: String,
            androidMajor: Int,
            deviceCategory: DeviceCapabilityCategory,
        ): WhitelistedHttpDiagnosticSender = WhitelistedHttpDiagnosticSender(
            endpoint,
            client,
            appVersion,
            androidMajor,
            deviceCategory,
            allowCleartextForTest = false,
        )

        internal fun forTest(
            endpoint: HttpUrl,
            client: OkHttpClient,
            appVersion: String = "test",
            androidMajor: Int = 35,
            deviceCategory: DeviceCapabilityCategory = DeviceCapabilityCategory.OTHER,
        ): WhitelistedHttpDiagnosticSender = WhitelistedHttpDiagnosticSender(
            endpoint,
            client,
            appVersion,
            androidMajor,
            deviceCategory,
            allowCleartextForTest = true,
        )
    }
}

/** Final network-boundary scan. Only compile-time stack symbols without card-number-like runs survive. */
internal object DiagnosticUploadStringScanner {
    private val CARD_NUMBER_LIKE = Regex("(?:[0-9][ -]?){12,19}")

    fun isSafe(diagnostic: SanitizedCrashDiagnostic): Boolean = diagnostic.frames.all { frame ->
        isSafeSymbol(frame.className) && isSafeSymbol(frame.methodName)
    }

    private fun isSafeSymbol(value: String): Boolean = value.length <= MAXIMUM_SYMBOL_CHARACTERS && '@' !in value && !CARD_NUMBER_LIKE.containsMatchIn(value)

    private const val MAXIMUM_SYMBOL_CHARACTERS = 240
}
