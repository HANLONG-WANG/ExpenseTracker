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
        "coordinates",
        "location",
        "address",
        "attachment",
        "filepath",
        "path",
        "hash",
        "uri",
        "transaction",
    )
    private val sensitiveType = Regex(
        "(?i)\\b(Money|Amount|Transaction(?!Id)|CardNumber|Pan|Cvc|Cvv|Password|Secret|" +
            "RecoveryPassword|SecretBytes|SensitivePlaintext|VaultFieldCiphertext|DeviceLedgerKeys|" +
            "RecoveryWrappedKeyMaterial|LocationSnapshot|Attachment(Content|Bytes)?|ByteArray)\\b",
    )
    private val anyClass = Regex("(?:data\\s+|value\\s+)?class\\s+(\\w+)\\s*\\(")
    private val stateClass = Regex("(?:data\\s+|value\\s+)?class\\s+(\\w*(?:Route|SavedState)\\w*)\\s*\\(")
    private val constructorField = Regex("(?:val|var)\\s+(\\w+)\\s*:\\s*([^,)=]+)")
    private val variableDeclaration = Regex("\\b(\\w+)\\s*:\\s*([A-Za-z0-9_?.<>]+)")
    private val aliasDeclaration = Regex(
        "(?:val|var)\\s+(\\w+)(?:\\s*:\\s*[A-Za-z0-9_?.<>]+)?\\s*=\\s*(\\w+)\\b",
    )
    private val daoPropertyAliasDeclaration = Regex(
        "(?:val|var)\\s+(\\w+)(?:\\s*:\\s*[A-Za-z0-9_?.<>]+)?\\s*=\\s*(?:\\w+\\.)+(\\w*Dao)\\b",
    )
    private val savedStateWrite = Regex(
        "(?i)\\b(\\w+)\\s*(?:\\[\\s*\"([^\"]+)\"\\s*]\\s*=|\\.\\s*set\\s*\\(\\s*\"([^\"]+)\"\\s*,)",
    )
    private val writeCall = Regex(
        "(?i)\\b(\\w+)\\s*\\.\\s*(insert|update|delete|upsert|replace|apply|write|mutate|persist|save)\\w*\\s*\\(",
    )
    private val daoTypedVariable = Regex("\\b(\\w+)\\s*:\\s*(?:[A-Za-z0-9_.]+\\.)?(\\w*Dao)\\b")
    private val coordinatorDeclaration = Regex(
        "\\b(?:class|object)\\s+\\w*FinancialMutationCoordinator\\b",
    )
    private val protectedFinancialSqlMutation = Regex(
        "(?is)\\b(?:INSERT\\s+(?:OR\\s+\\w+\\s+)?INTO|UPDATE|DELETE\\s+FROM|REPLACE\\s+INTO)\\s+" +
            "(?:book(?:_commit|_commit_parent)?|command_receipt|business_transaction|transaction_revision|" +
            "journal_entry|posting|economic_effect|budget_effect|project_effect|goal_effect|statement_effect|" +
            "loan_effect|settlement_effect|refund_allocation|goal_movement|budget_adjustment)\\b",
    )
    private val screenReference = Regex(
        "(?:ScreenId\\s*\\(\\s*|screenId\\s*=\\s*)\"([A-Z][A-Z0-9]*-\\d{3})\"",
    )
    private const val GOVERNED_MATERIAL_COMPONENT =
        "Button|FilledTonalButton|ElevatedButton|OutlinedButton|TextButton|IconButton|" +
            "FloatingActionButton|ExtendedFloatingActionButton|TextField|OutlinedTextField|SearchBar|" +
            "DockedSearchBar|Card|ElevatedCard|OutlinedCard|ListItem|AssistChip|FilterChip|InputChip|" +
            "SuggestionChip|Badge|BadgedBox|Dialog|AlertDialog|BasicAlertDialog|ModalBottomSheet|" +
            "Snackbar|SnackbarHost|Scaffold|TopAppBar|CenterAlignedTopAppBar|MediumTopAppBar|LargeTopAppBar|" +
            "NavigationBar|NavigationRail|Tab|PrimaryTabRow|SecondaryTabRow|DatePicker|DatePickerDialog|" +
            "TimePicker|CircularProgressIndicator|LinearProgressIndicator|Surface"
    private const val GOVERNED_COMPONENT_DECLARATION =
        "AmountText|MoneyStack|MetricCard|StatusBadge|CategoryGrid|CategoryTile|JournalTransactionRow|" +
            "AccountSummaryCard|ProgressSummary|MoneyExpressionField|FilterBuilder|AttachmentField|" +
            "LocationField|ChartCard|AccessibleDataTable|MapPanel|OperationProgressPanel|SensitiveValueField|" +
            "HighRiskConfirmation|LedgerScaffold|LedgerTopAppBar|LedgerNavigationBar|LedgerSaveFab|" +
            "LedgerButton|LedgerIconButton|LedgerTextField|SearchField|SelectorField|DateTimeZoneField|" +
            "FormSection|ValidationSummary|LedgerCard|LedgerChip|LedgerBanner|LedgerLoadingState|" +
            "LedgerEmptyState|LedgerErrorState|LedgerProgressIndicator|LedgerSnackbarHost|LedgerTabRow|" +
            "LedgerDialog|LedgerBottomSheet|LedgerDatePickerDialog|LedgerTimePickerDialog"

    @Suppress("LongMethod", "CyclomaticComplexMethod")
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
            Regex("(?m)^import\\s+app\\.ledger\\.core\\.security(?:\\.|$)")
                .findAll(source)
                .forEach {
                    report(
                        "ARCH-FEATURE-SECURITY",
                        it,
                        "feature source cannot obtain keys, database sessions, or headless security leases",
                    )
                }
            Regex(
                "(?m)^import\\s+androidx\\.compose\\.material3\\.(?:\\*|$GOVERNED_MATERIAL_COMPONENT)\\b|" +
                    "androidx\\.compose\\.material3\\.(?:$GOVERNED_MATERIAL_COMPONENT)\\b",
            )
                .findAll(source)
                .forEach { report("UI-WRAPPER", it, "feature source bypasses a governed design-system wrapper") }
            Regex(
                "\\bColor\\s*\\(|\\bColor\\.(?:Black|White|Red|Green|Blue|Yellow|Gray|Grey|Magenta|Cyan|Transparent)\\b|" +
                    "\"#[0-9A-Fa-f]{6,8}\"",
            )
                .findAll(source)
                .forEach { report("UI-COLOR-LITERAL", it, "feature source contains a hard-coded color") }
            Regex("(?<![A-Za-z0-9_])(?:\\d+(?:\\.\\d+)?)\\.dp\\b")
                .findAll(source)
                .forEach { report("UI-SPACING-LITERAL", it, "feature source contains a hard-coded dp value") }
            Regex("\\bMaterialTheme\\b")
                .findAll(source)
                .forEach { report("UI-LOCAL-THEME", it, "feature source uses MaterialTheme instead of LedgerTheme") }
            Regex("(?m)^import\\s+androidx\\.compose\\.material\\.icons(?:\\.|$)|androidx\\.compose\\.material\\.icons\\.")
                .findAll(source)
                .forEach { report("UI-ICON-REGISTRY", it, "feature source bypasses the unified LedgerIcon registry") }
            Regex(
                "\\b(?:SwipeToDismissBox|SwipeToDismiss|rememberSwipeToDismissBoxState|rememberDismissState|" +
                    "DismissValue|DismissDirection)\\b",
            )
                .findAll(source)
                .forEach { report("UI-SWIPE-DELETE", it, "feature source introduces forbidden swipe-to-delete behavior") }
            Regex("(?m)^(?:public\\s+|private\\s+|internal\\s+)?(?:suspend\\s+)?fun\\s+(?:$GOVERNED_COMPONENT_DECLARATION)\\s*\\(")
                .findAll(source)
                .forEach { report("UI-COMPONENT-DUPLICATE", it, "feature source duplicates a governed design-system component") }
            Regex(
                "(?m)^import\\s+app\\.ledger\\.finance\\.(?:data(?:\\.|$)|application\\.(?:AtomicFinancialCommitRepository|" +
                    "CommandReceiptRepository|FinancialPlanningSnapshotRepository))",
            )
                .findAll(source)
                .forEach { report("FINANCE-WRITE-PORT", it, "feature source may depend only on the financial command handler/use case") }
            Regex("(?m)^import\\s+app\\.ledger\\.core\\.(?:files|geo)(?:\\.|$)")
                .findAll(source)
                .forEach {
                    report(
                        "ARCH-FEATURE-INFRASTRUCTURE",
                        it,
                        "feature source must consume attachment/location application ports or injected UI boundaries",
                    )
                }
        }

        Regex("\\bManifest\\.permission\\.ACCESS_BACKGROUND_LOCATION\\b|\\bACCESS_BACKGROUND_LOCATION\\b")
            .findAll(source)
            .forEach {
                report(
                    "PRIVACY-BACKGROUND-LOCATION",
                    it,
                    "background location permission is outside the foreground-only location contract",
                )
            }

        if (!normalizedPath.startsWith("core/files/src/main/")) {
            Regex("(?m)^import\\s+coil3(?:\\.|$)")
                .findAll(source)
                .forEach { report("ARCH-ATTACHMENT-SDK", it, "Coil attachment integration is owned by core:files") }
        } else {
            Regex(
                "\\bEnvironment\\.getExternalStorage|\\bMediaStore\\.|\\bgetExternalFilesDir\\s*\\(|" +
                    "\\bexternalCacheDir\\b|\\bgetExternalCacheDir\\s*\\(",
            )
                .findAll(source)
                .forEach {
                    report(
                        "PRIVACY-FILES-SHARED-STORAGE",
                        it,
                        "encrypted attachment objects and thumbnails must remain in app-private storage",
                    )
                }
        }

        if (!normalizedPath.startsWith("core/geo/src/main/")) {
            Regex("(?m)^import\\s+(?:org\\.maplibre|com\\.google\\.android\\.gms\\.location)(?:\\.|$)")
                .findAll(source)
                .forEach { report("ARCH-GEO-SDK", it, "MapLibre and fused-location SDK access is owned by core:geo") }
        }

        val ownsFinancialWritePorts = normalizedPath.startsWith("finance/application/src/main/") ||
            normalizedPath.startsWith("finance/data/src/main/")
        if (!ownsFinancialWritePorts) {
            Regex(
                "(?m)^import\\s+app\\.ledger\\.finance\\.application\\.(?:AtomicFinancialCommitRepository|" +
                    "CommandReceiptRepository|FinancialPlanningPort|FinancialPlanningSnapshotRepository)\\b",
            )
                .findAll(source)
                .forEach {
                    report(
                        "FINANCE-WRITE-PORT",
                        it,
                        "only finance application/data may depend on planning or atomic financial persistence ports",
                    )
                }
        }

        val ownsFinancialSql = normalizedPath.startsWith("finance/data/src/main/") ||
            normalizedPath.startsWith("core/database/src/main/")
        if (!ownsFinancialSql) {
            protectedFinancialSqlMutation.findAll(source).forEach {
                report("FINANCE-SQL-WRITE", it, "financial SQL mutations are owned by finance:data/core:database only")
            }
        }

        Regex("\\.testTag\\s*\\(\\s*([^\\n)]*)")
            .findAll(source)
            .filter { match ->
                val argument = match.groupValues[1].trim()
                !Regex("LedgerTestTags\\.[A-Z][A-Z0-9_]*").matches(argument) &&
                    !Regex("\"[a-z][a-z0-9_]{2,63}\"").matches(argument)
            }
            .forEach { report("PRIVACY-TEST-TAG", it, "test tags must be fixed semantic IDs without runtime or sensitive values") }

        if (Regex("^(?:finance|analytics|transfer)/domain/").containsMatchIn(normalizedPath)) {
            Regex(
                "(?m)^import\\s+(?:androidx?(?:\\.|$)|dagger(?:\\.|$)|com\\.google\\.dagger(?:\\.|$)|" +
                    "okhttp3(?:\\.|$)|retrofit2(?:\\.|$)|io\\.reactivex(?:\\.|$)|app\\.ledger\\.core\\.network(?:\\.|$))",
            )
                .findAll(source)
                .forEach { report("ARCH-DOMAIN-FRAMEWORK", it, "domain source imports a forbidden framework API") }
            Regex(
                "\\b(?:Map|MutableMap)\\s*<\\s*String\\s*,\\s*(?:Any|Any\\?)\\s*>|" +
                    "\\b(?:JsonObject|JsonElement|JSONObject)\\b|" +
                    "\\b(?:payload|attributes|properties)\\s*:\\s*(?:Map|MutableMap)\\s*<\\s*String\\s*,",
            )
                .findAll(source)
                .forEach {
                    report(
                        "ARCH-DOMAIN-GENERIC-PAYLOAD",
                        it,
                        "domain core fields must use closed typed models instead of generic JSON/property bags",
                    )
                }
        }

        findings += authoritativeMoneyFindings(normalizedPath, source)

        Regex("(?i)\\b(?:password|pan|cardNumber|cvc|cvv)\\s*:\\s*String\\b")
            .findAll(source)
            .forEach {
                report(
                    "PRIVACY-RAW-SECRET",
                    it,
                    "passwords and complete payment-card secrets require bounded non-String wrappers",
                )
            }

        val telemetryContext = normalizedPath.contains("/telemetry/") ||
            Regex("(?i)\\b(?:class|object|interface)\\s+\\w*Telemetry\\w*")
                .containsMatchIn(source)
        if (telemetryContext) {
            Regex("\\b(?:Map\\s*<|MutableMap\\s*<|mapOf\\s*\\(|mutableMapOf\\s*\\()")
                .findAll(source)
                .forEach { report("PRIVACY-TELEMETRY-MAP", it, "telemetry must use typed white-listed events, not a generic Map") }
        }

        val loggingAliases = Regex(
            "(?m)^import\\s+(?:android\\.util\\.Log|timber\\.log\\.Timber|java\\.util\\.logging\\.Logger|" +
                "org\\.slf4j\\.LoggerFactory)(?:\\s+as\\s+(\\w+))?",
        ).findAll(source).map { match -> match.groupValues[1].ifEmpty { match.value.substringAfterLast('.') } }.toSet()
        Regex(
            "(?:android\\.util\\.Log|\\bLog\\.[vdiewtf]\\s*\\(|\\bTimber\\.|\\bprintln\\s*\\(|" +
                "\\bprintStackTrace\\s*\\(|\\bLoggerFactory\\.|\\bLogger\\.getLogger\\s*\\()",
        ).findAll(source).forEach {
            report("PRIVACY-LOGGING", it, "ordinary logging APIs are forbidden in production sources")
        }
        loggingAliases.forEach { alias ->
            Regex("\\b${Regex.escape(alias)}\\s*\\.").findAll(source).forEach {
                report("PRIVACY-LOGGING", it, "aliased ordinary logging API is forbidden in production sources")
            }
        }

        val sensitiveDeclaredTypes = sensitiveDeclaredTypes(source)
        stateClass.findAll(source).forEach { declaration ->
            val constructor = source.balancedParentheses(declaration.range.last) ?: return@forEach
            constructorField.findAll(constructor).forEach { field ->
                val fieldName = field.groupValues[1]
                val fieldType = field.groupValues[2].trim()
                val rawType = fieldType.substringBefore('<').removeSuffix("?").substringAfterLast('.')
                val sensitive = fieldName.lowercase() in sensitiveFieldNames ||
                    sensitiveType.containsMatchIn(fieldType) ||
                    rawType in sensitiveDeclaredTypes ||
                    !isOpaqueStateType(fieldType)
                if (sensitive) {
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

        val variableTypes = variableDeclaration.findAll(source).associate { match ->
            match.groupValues[1] to match.groupValues[2].removeSuffix("?").substringAfterLast('.')
        }
        val savedStateReceivers = variableTypes.filterValues { type -> type == "SavedStateHandle" }.keys.toMutableSet()
        propagateAliases(source, savedStateReceivers)
        savedStateWrite.findAll(source).forEach { access ->
            if (access.groupValues[1] in savedStateReceivers) {
                val key = access.groupValues.drop(2).first(String::isNotEmpty)
                val tail = source.substring(access.range.last + 1, source.lineEndAfter(access.range.last))
                val sensitiveValue = sensitiveType.containsMatchIn(tail) ||
                    Regex("\\b(?:Map\\s*<|MutableMap\\s*<|mapOf\\s*\\(|mutableMapOf\\s*\\()").containsMatchIn(tail) ||
                    variableTypes.any { (name, type) ->
                        Regex("\\b${Regex.escape(name)}\\b").containsMatchIn(tail) &&
                            (type in sensitiveDeclaredTypes || !isOpaqueStateType(type))
                    }
                if (key.lowercase() in sensitiveFieldNames || sensitiveValue) {
                    report("PRIVACY-SAVEDSTATE-KEY", access, "SavedStateHandle write '$key' contains sensitive state")
                }
            }
        }

        val daoTypeAliases = Regex("\\btypealias\\s+(\\w+)\\s*=\\s*(?:[A-Za-z0-9_.]+\\.)?(\\w*Dao)\\b")
            .findAll(source)
            .map { it.groupValues[1] }
            .toMutableSet()
        Regex("(?m)^import\\s+[A-Za-z0-9_.]+\\.(\\w*Dao)\\s+as\\s+(\\w+)")
            .findAll(source)
            .mapTo(daoTypeAliases) { it.groupValues[2] }
        val daoReceivers = daoTypedVariable.findAll(source).map { it.groupValues[1] }.toMutableSet()
        variableTypes.filterValues { type -> type.endsWith("Dao") || type in daoTypeAliases }.keys.forEach(daoReceivers::add)
        daoPropertyAliasDeclaration.findAll(source).mapTo(daoReceivers) { it.groupValues[1] }
        propagateAliases(source, daoReceivers)
        val coordinatorRanges = coordinatorDeclaration.findAll(source).mapNotNull { declaration ->
            val openingBrace = source.indexOf('{', declaration.range.last + 1)
            val intervening = if (openingBrace < 0) "" else source.substring(declaration.range.last + 1, openingBrace)
            if (openingBrace < 0 || Regex("(?m)^\\s*(?:class|object|interface|fun)\\b").containsMatchIn(intervening)) {
                null
            } else {
                source.balancedBraces(openingBrace)
            }
        }.toList()
        writeCall.findAll(source).forEach { call ->
            val receiver = call.groupValues[1]
            if ((receiver in daoReceivers || receiver.endsWith("Dao", ignoreCase = true)) &&
                coordinatorRanges.none { range -> call.range.first in range }
            ) {
                report(
                    "FINANCE-COORDINATOR",
                    call,
                    "$receiver.${call.groupValues[2]}* is called outside the owning FinancialMutationCoordinator scope",
                )
            }
        }

        if (!normalizedPath.startsWith("core/time/")) {
            Regex("\\b(?:Clock\\.system(?:UTC|DefaultZone)?|Instant\\.now|LocalDate(?:Time)?\\.now)\\s*\\(")
                .findAll(source)
                .forEach { report("DETERMINISM-CLOCK", it, "inject the project clock instead of reading system time") }
        }
        val directRandom = if (normalizedPath.startsWith("core/security/src/main/")) {
            Regex("\\b(?:UUID\\.randomUUID\\s*\\(|Random\\.Default\\b)")
        } else {
            Regex("\\b(?:UUID\\.randomUUID\\s*\\(|Random\\.Default\\b|SecureRandom\\s*\\()")
        }
        directRandom
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
        Regex(
            "(?<!CheckedArithmetic)\\.(?:sum|sumOf|fold|foldIndexed|reduce|reduceIndexed|runningFold|runningReduce)" +
                "\\s*(?:\\(|\\{)",
        )
            .findAll(source).forEach { match ->
                findings += SourcePolicyFinding(
                    "MONEY-UNCHECKED-SUM",
                    path,
                    source.lineAt(match.range.first),
                    "money aggregation must use CheckedArithmetic or an explicit BigInteger accumulator",
                )
            }
        val longAccumulators = Regex("\\bvar\\s+(\\w+)\\s*(?::\\s*Long)?\\s*=\\s*[^\\n;]*?\\b-?\\d+L\\b")
            .findAll(source)
            .map { it.groupValues[1] }
            .toMutableSet()
        Regex("\\bvar\\s+(\\w+)\\s*:\\s*Long\\b")
            .findAll(source)
            .mapTo(longAccumulators) { it.groupValues[1] }
        Regex("\\bvar\\s+(\\w*(?:total|sum|balance|amount|accumul)\\w*)\\s*=")
            .findAll(source)
            .mapTo(longAccumulators) { it.groupValues[1] }
        longAccumulators.forEach { accumulator ->
            val escaped = Regex.escape(accumulator)
            listOf(
                Regex("\\b$escaped\\s*\\+="),
                Regex("\\b$escaped\\s*=\\s*$escaped\\s*\\+"),
                Regex("\\b$escaped\\s*=\\s*[^\\n;]+\\+\\s*$escaped\\b"),
                Regex("\\b$escaped(?:\\+\\+|--)"),
            ).forEach { unsafePattern ->
                unsafePattern.findAll(source).forEach { match ->
                    findings += SourcePolicyFinding(
                        "MONEY-UNCHECKED-ACCUMULATION",
                        path,
                        source.lineAt(match.range.first),
                        "Long accumulator '$accumulator' must use CheckedArithmetic or BigInteger",
                    )
                }
            }
        }
        return findings
    }

    private fun sensitiveDeclaredTypes(source: String): Set<String> {
        val sensitive = mutableSetOf<String>()
        var changed: Boolean
        do {
            changed = false
            anyClass.findAll(source).forEach { declaration ->
                val constructor = source.balancedParentheses(declaration.range.last) ?: return@forEach
                val containsSensitive = constructorField.findAll(constructor).any { field ->
                    val name = field.groupValues[1]
                    val type = field.groupValues[2].trim().substringBefore('<').removeSuffix("?").substringAfterLast('.')
                    name.lowercase() in sensitiveFieldNames || sensitiveType.containsMatchIn(type) || type in sensitive
                }
                if (containsSensitive && sensitive.add(declaration.groupValues[1])) changed = true
            }
        } while (changed)
        return sensitive
    }

    private fun isOpaqueStateType(type: String): Boolean {
        val normalized = type.replace(" ", "").removeSuffix("?").substringAfterLast('.')
        return normalized in setOf("Boolean", "Int", "Long", "String", "StableId", "InternalId", "CommandId", "RevisionId") ||
            Regex("[A-Z][A-Za-z0-9]*Id").matches(normalized)
    }

    private fun propagateAliases(source: String, receivers: MutableSet<String>) {
        var changed: Boolean
        do {
            changed = false
            aliasDeclaration.findAll(source).forEach { alias ->
                if (alias.groupValues[2] in receivers && receivers.add(alias.groupValues[1])) changed = true
            }
        } while (changed)
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

    private fun String.balancedBraces(openingIndex: Int): IntRange? {
        var depth = 0
        for (index in openingIndex until length) {
            when (this[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return openingIndex..index
                }
            }
        }
        return null
    }

    private fun String.lineEndAfter(index: Int): Int {
        val newline = indexOf('\n', index.coerceAtLeast(0))
        return if (newline < 0) length else newline
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
