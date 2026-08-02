package app.ledger.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import app.ledger.app.settings.LedgerAppSettings
import app.ledger.app.settings.OnboardingStepProto
import app.ledger.app.settings.SessionRestorePolicyProto
import app.ledger.feature.onboarding.OnboardingStep
import com.google.protobuf.InvalidProtocolBufferException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.io.InputStream
import java.io.OutputStream

internal object LedgerAppSettingsSerializer : Serializer<LedgerAppSettings> {
    override val defaultValue: LedgerAppSettings = LedgerAppSettings.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): LedgerAppSettings = try {
        LedgerAppSettings.parseFrom(input)
    } catch (error: InvalidProtocolBufferException) {
        throw androidx.datastore.core.CorruptionException("ledger settings are corrupt", error)
    }

    override suspend fun writeTo(t: LedgerAppSettings, output: OutputStream) = t.writeTo(output)
}

internal class AppSettingsRepository(context: Context) {
    private val store: DataStore<LedgerAppSettings> = DataStoreFactory.create(
        serializer = LedgerAppSettingsSerializer,
        produceFile = { context.applicationContext.filesDir.resolve(FILE_NAME) },
    )

    val data: Flow<LedgerAppSettings> = store.data

    suspend fun current(): LedgerAppSettings = data.first()

    suspend fun update(block: (LedgerAppSettings.Builder) -> Unit): LedgerAppSettings = store.updateData { existing ->
        existing.toBuilder().also(block).build()
    }

    suspend fun reset(): LedgerAppSettings = store.updateData { LedgerAppSettings.getDefaultInstance() }

    suspend fun saveStep(step: OnboardingStep) = update { it.onboardingStep = step.toProto() }

    suspend fun markUnsavedContentLost() = update { it.unsavedContentLossPending = true }

    suspend fun consumeUnsavedContentLoss(): Boolean {
        var pending = false
        update {
            pending = it.unsavedContentLossPending
            it.unsavedContentLossPending = false
        }
        return pending
    }

    companion object {
        private const val FILE_NAME = "ledger_app_settings.pb"
    }
}

internal fun OnboardingStep.toProto(): OnboardingStepProto = when (this) {
    OnboardingStep.LANGUAGE -> OnboardingStepProto.ONBOARDING_STEP_LANGUAGE
    OnboardingStep.BASE_CURRENCY -> OnboardingStepProto.ONBOARDING_STEP_BASE_CURRENCY
    OnboardingStep.TIME_ZONE -> OnboardingStepProto.ONBOARDING_STEP_TIME_ZONE
    OnboardingStep.PRIVACY_POLICY -> OnboardingStepProto.ONBOARDING_STEP_PRIVACY_POLICY
    OnboardingStep.TELEMETRY -> OnboardingStepProto.ONBOARDING_STEP_TELEMETRY
    OnboardingStep.APP_LOCK -> OnboardingStepProto.ONBOARDING_STEP_APP_LOCK
    OnboardingStep.BACKUP -> OnboardingStepProto.ONBOARDING_STEP_BACKUP
    OnboardingStep.ACCOUNT -> OnboardingStepProto.ONBOARDING_STEP_ACCOUNT
    OnboardingStep.CATEGORY -> OnboardingStepProto.ONBOARDING_STEP_CATEGORY
    OnboardingStep.COMPLETE -> OnboardingStepProto.ONBOARDING_STEP_COMPLETE
}

internal fun OnboardingStepProto.toDomain(): OnboardingStep = when (this) {
    OnboardingStepProto.ONBOARDING_STEP_LANGUAGE, OnboardingStepProto.UNRECOGNIZED -> OnboardingStep.LANGUAGE
    OnboardingStepProto.ONBOARDING_STEP_BASE_CURRENCY -> OnboardingStep.BASE_CURRENCY
    OnboardingStepProto.ONBOARDING_STEP_TIME_ZONE -> OnboardingStep.TIME_ZONE
    OnboardingStepProto.ONBOARDING_STEP_PRIVACY_POLICY -> OnboardingStep.PRIVACY_POLICY
    OnboardingStepProto.ONBOARDING_STEP_TELEMETRY -> OnboardingStep.TELEMETRY
    OnboardingStepProto.ONBOARDING_STEP_APP_LOCK -> OnboardingStep.APP_LOCK
    OnboardingStepProto.ONBOARDING_STEP_BACKUP -> OnboardingStep.BACKUP
    OnboardingStepProto.ONBOARDING_STEP_ACCOUNT -> OnboardingStep.ACCOUNT
    OnboardingStepProto.ONBOARDING_STEP_CATEGORY -> OnboardingStep.CATEGORY
    OnboardingStepProto.ONBOARDING_STEP_COMPLETE -> OnboardingStep.COMPLETE
}

internal val LedgerAppSettings.alwaysRestoreLastPage: Boolean
    get() = restorePolicy == SessionRestorePolicyProto.SESSION_RESTORE_ALWAYS_LAST_PAGE

internal fun LedgerAppSettings.shouldRestoreNavigationAfterColdStart(): Boolean = alwaysRestoreLastPage && hasNavigationSnapshot()
