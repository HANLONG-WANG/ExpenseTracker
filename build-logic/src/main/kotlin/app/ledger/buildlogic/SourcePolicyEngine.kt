package app.ledger.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

internal data class SourcePolicyFinding(
    val ruleId: String,
    val path: String,
    val line: Int,
    val message: String,
) {
    fun diagnostic(): String = "$path:$line [$ruleId] $message"
}

internal object SourcePolicyEngine {
    private val sensitiveFieldNames = setOf(
        "amount",
        "memo",
        "note",
        "remark",
        "pan",
        "cardnumber",
        "cvc",
        "cvv",
        "password",
        "secret",
        "latitude",
        "longitude",
        "location",
        "address",
        "attachment",
        "transaction",
    )
    private val sensitiveType = Regex(
        "(?i)\\b(Money|Amount|Transaction(?!Id)|CardNumber|Pan|Cvc|Cvv|Password|Secret|" +
            "LocationSnapshot|Attachment(Content|Bytes)?|ByteArray)\\b",
    )
    private val stateClass = Regex("(?:data\\s+)?class\\s+(\\w*(?:Route|SavedState)\\w*)\\s*\\(")
    private val constructorField = Regex("(?:val|var)\\s+(\\w+)\\s*:\\s*([^,)=]+)")
    private val savedStateAccess = Regex(
        "(?i)(?:savedStateHandle\\s*\\[\\s*\"([^\"]+)\"|savedStateHandle\\s*\\.\\s*set\\s*\\(\\s*\"([^\"]+)\")",
    )
    private val daoWriteCall = Regex(
        "(?i)\\b(\\w*Dao)\\s*\\.\\s*(insert|update|delete|upsert|replace|apply|write|mutate|persist|save)\\w*\\s*\\(",
    )
    private val coordinatorDeclaration = Regex(
        "\\b(?:class|object)\\s+\\w*FinancialMutationCoordinator\\b",
    )
    private val screenReference = Regex(
        "(?:ScreenId\\s*\\(\\s*|screenId\\s*=\\s*)\"([A-Z][A-Z0-9]*-\\d{3})\"",
    )

    fun scan(
        path: String,
        source: String,
        allowedScreenIds: Set<String> = emptySet(),
    ): List<SourcePolicyFinding> {
        val normalizedPath = path.replace('\\', '/')
        val findings = mutableListOf<SourcePolicyFinding>()

        fun report(ruleId: String, match: MatchResult, message: String) {
            findings += SourcePolicyFinding(ruleId, normalizedPath, source.lineAt(match.range.first), message)
        }

        if (normalizedPath.startsWith("feature/")) {
            Regex("(?m)^import\\s+(?:app\\.ledger\\..*\\.(?:data|database)(?:\\.|$)|androidx\\.room(?:\\.|$))")
                .findAll(source)
                .forEach { report("ARCH-FEATURE-DATA", it, "feature source imports a data/Room API") }
            Regex(
                "(?m)^import\\s+androidx\\.compose\\.material3\\.(?:\\*|Button|IconButton|TextField|Card|Dialog|" +
                    "ModalBottomSheet|Snackbar|NavigationBar|TopAppBar|Tab|DatePicker|TimePicker|CircularProgressIndicator|" +
                    "LinearProgressIndicator)\\b",
            )
                .findAll(source)
                .forEach { report("UI-WRAPPER", it, "feature source bypasses a governed design-system wrapper") }
            Regex("\\bColor\\s*\\(|\\bColor\\.(?:Black|White|Red|Green|Blue|Yellow|Gray|Grey|Magenta|Cyan|Transparent)\\b")
                .findAll(source)
                .forEach { report("UI-COLOR-LITERAL", it, "feature source contains a hard-coded color") }
            Regex("(?<![A-Za-z0-9_])(?:\\d+(?:\\.\\d+)?)\\.dp\\b")
                .findAll(source)
                .forEach { report("UI-SPACING-LITERAL", it, "feature source contains a hard-coded dp value") }
            Regex("\\bMaterialTheme\\s*(?:\\(|\\{)")
                .findAll(source)
                .forEach { report("UI-LOCAL-THEME", it, "feature source creates a local MaterialTheme") }
        }

        if (Regex("^(?:finance|analytics|transfer)/domain/").containsMatchIn(normalizedPath)) {
            Regex("(?m)^import\\s+androidx?(?:\\.|$)")
                .findAll(source)
                .forEach { report("ARCH-DOMAIN-ANDROID", it, "domain source imports Android APIs") }
        }

        findings += authoritativeMoneyFindings(normalizedPath, source)

        if (normalizedPath.startsWith("core/telemetry/")) {
            Regex("\\b(?:Map\\s*<|mapOf\\s*\\()")
                .findAll(source)
                .forEach { report("PRIVACY-TELEMETRY-MAP", it, "telemetry must use typed white-listed events, not a generic Map") }
        }

        Regex(
            "(?:android\\.util\\.Log|\\bLog\\.[vdiewtf]\\s*\\(|\\bTimber\\.|\\bprintln\\s*\\(|" +
                "\\bprintStackTrace\\s*\\()",
        ).findAll(source).forEach {
            report("PRIVACY-LOGGING", it, "ordinary logging APIs are forbidden in production sources")
        }

        stateClass.findAll(source).forEach { declaration ->
            val constructor = source.balancedParentheses(declaration.range.last) ?: return@forEach
            constructorField.findAll(constructor).forEach { field ->
                val fieldName = field.groupValues[1]
                val fieldType = field.groupValues[2]
                if (fieldName.lowercase() in sensitiveFieldNames || sensitiveType.containsMatchIn(fieldType)) {
                    val absolute = declaration.range.last + field.range.first
                    findings += SourcePolicyFinding(
                        "PRIVACY-ROUTE-STATE",
                        normalizedPath,
                        source.lineAt(absolute),
                        "${declaration.groupValues[1]} contains sensitive field $fieldName: ${fieldType.trim()}",
                    )
                }
            }
        }

        savedStateAccess.findAll(source).forEach { access ->
            val key = access.groupValues.drop(1).first(String::isNotEmpty)
            if (key.lowercase() in sensitiveFieldNames) {
                report("PRIVACY-SAVEDSTATE-KEY", access, "SavedStateHandle key '$key' is sensitive")
            }
        }

        if (!coordinatorDeclaration.containsMatchIn(source)) {
            daoWriteCall.findAll(source).forEach { call ->
                report(
                    "FINANCE-COORDINATOR",
                    call,
                    "${call.groupValues[1]}.${call.groupValues[2]}* is called outside FinancialMutationCoordinator",
                )
            }
        }

        if (!normalizedPath.startsWith("core/time/")) {
            Regex("\\b(?:Clock\\.system(?:UTC|DefaultZone)?|Instant\\.now|LocalDate(?:Time)?\\.now)\\s*\\(")
                .findAll(source)
                .forEach { report("DETERMINISM-CLOCK", it, "inject the project clock instead of reading system time") }
        }
        Regex("\\b(?:UUID\\.randomUUID\\s*\\(|Random\\.Default\\b|SecureRandom\\s*\\()")
            .findAll(source)
            .forEach { report("DETERMINISM-ID", it, "inject the project ID/random source") }

        if (allowedScreenIds.isNotEmpty()) {
            screenReference.findAll(source).forEach { reference ->
                val screenId = reference.groupValues[1]
                if (screenId !in allowedScreenIds) {
                    report("UI-SCREEN-ID", reference, "screen ID $screenId is absent from the route contract")
                }
            }
        }

        return findings.distinct()
    }

    private fun authoritativeMoneyFindings(path: String, source: String): List<SourcePolicyFinding> {
        if (!path.startsWith("core/money/src/main/") && !path.startsWith("finance/domain/src/main/")) {
            return emptyList()
        }
        val findings = mutableListOf<SourcePolicyFinding>()
        Regex("\\b(?:Float|Double)\\b|\\bto(?:Float|Double)\\s*\\(").findAll(source).forEach { match ->
            findings += SourcePolicyFinding(
                "MONEY-BINARY-FLOAT",
                path,
                source.lineAt(match.range.first),
                "authoritative money code must use Long minor units or decimal/integer exact arithmetic",
            )
        }
        Regex("(?<!CheckedArithmetic)\\.(?:sum|sumOf)\\s*\\(").findAll(source).forEach { match ->
            findings += SourcePolicyFinding(
                "MONEY-UNCHECKED-SUM",
                path,
                source.lineAt(match.range.first),
                "money aggregation must use CheckedArithmetic or a BigInteger accumulator",
            )
        }
        return findings
    }

    private fun String.lineAt(index: Int): Int = take(index.coerceAtLeast(0)).count { it == '\n' } + 1

    private fun String.balancedParentheses(openingIndex: Int): String? {
        var depth = 0
        for (index in openingIndex until length) {
            when (this[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return substring(openingIndex, index + 1)
                }
            }
        }
        return null
    }
}

abstract class VerifySourcePoliciesTask : DefaultTask() {
    @get:Internal
    abstract val rootDirectory: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val screenContract: RegularFileProperty

    @get:Optional
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val fixtureDirectory: DirectoryProperty

    @TaskAction
    fun verify() {
        val root = rootDirectory.get().asFile
        val fixtureRoot = fixtureDirectory.orNull?.asFile
        val contract = screenContract.get().asFile.readText()
        val allowedScreenIds = Regex("(?m)^\\s*-\\s+id:\\s*([A-Z0-9-]+)\\s*$")
            .findAll(contract)
            .map { it.groupValues[1] }
            .toSet()
        val findings = sourceFiles.files
            .filter(File::isFile)
            .sortedBy(File::getPath)
            .flatMap { file ->
                val relative = when {
                    fixtureRoot != null && file.toPath().startsWith(fixtureRoot.toPath()) ->
                        file.relativeTo(fixtureRoot).invariantSeparatorsPath
                    file.toPath().startsWith(root.toPath()) -> file.relativeTo(root).invariantSeparatorsPath
                    else -> file.invariantSeparatorsPath
                }
                SourcePolicyEngine.scan(relative, file.readText(), allowedScreenIds)
            }
        if (findings.isNotEmpty()) {
            throw GradleException(findings.joinToString(separator = "\n", transform = SourcePolicyFinding::diagnostic))
        }
        logger.lifecycle(
            "Source policy verification passed: ${sourceFiles.files.count(File::isFile)} files, " +
                "${allowedScreenIds.size} registered screen IDs.",
        )
    }
}
