package app.ledger.core.navigation

import androidx.compose.runtime.Immutable
import androidx.navigation3.runtime.NavKey
import app.ledger.core.common.StableId
import java.time.YearMonth

@JvmInline
public value class ScreenId(public val value: String) {
    init {
        require(SCREEN_ID.matches(value)) { "invalid registered screen ID" }
    }

    private companion object {
        val SCREEN_ID = Regex("[A-Z][A-Z0-9]*-[0-9]{3}")
    }
}

public enum class LedgerPresentation {
    BOTTOMSHEET,
    DIALOG,
    EMBEDDED,
    FULLSCREEN,
    MODE,
    ROOT,
    SHEET,
    SYSTEM,
    SYSTEMACTIVITY,
    SYSTEMDIALOG,
    TOPLEVEL,
}

public enum class RouteArgumentKind {
    STABLE_ID,
    ENUM,
    NAMED_ENUM,
    YEAR_MONTH,
    OPAQUE_KEY,
    ENUM_MASK,
    POSITIVE_INT,
}

@Immutable
public data class ScreenParameterSpec(
    val name: String,
    val kind: RouteArgumentKind,
    val optional: Boolean,
    val allowedValues: List<String>,
)

@Immutable
public data class ScreenContract(
    val screenId: ScreenId,
    val group: String,
    val module: String,
    val routePattern: String,
    val title: String,
    val presentation: LedgerPresentation,
    val parameters: List<ScreenParameterSpec>,
    val resultType: String?,
    val requiredStates: List<String>,
    val primaryComponents: List<String>,
    val notes: List<String>,
)

public sealed interface SafeRouteArgument {
    public val kind: RouteArgumentKind

    public val encoded: String
}

@Immutable
public data class StableIdArgument(val value: StableId) : SafeRouteArgument {
    override val kind: RouteArgumentKind = RouteArgumentKind.STABLE_ID
    override val encoded: String = value.toString()
}

@Immutable
public data class YearMonthArgument(val value: YearMonth) : SafeRouteArgument {
    override val kind: RouteArgumentKind = RouteArgumentKind.YEAR_MONTH
    override val encoded: String = "%04d%02d".format(value.year, value.monthValue)
}

@JvmInline
public value class EnumMaskArgument private constructor(private val value: Int) : SafeRouteArgument {
    override val kind: RouteArgumentKind
        get() = RouteArgumentKind.ENUM_MASK
    override val encoded: String
        get() = value.toString()

    public companion object {
        public fun fromBits(value: Int): EnumMaskArgument {
            require(value in 1..MAX_MASK) { "enum mask is outside the contract range" }
            return EnumMaskArgument(value)
        }

        private const val MAX_MASK: Int = 0xFFFF
    }
}

@JvmInline
public value class PositiveIntArgument private constructor(private val value: Int) : SafeRouteArgument {
    override val kind: RouteArgumentKind
        get() = RouteArgumentKind.POSITIVE_INT
    override val encoded: String
        get() = value.toString()

    public companion object {
        public fun fromPositive(value: Int): PositiveIntArgument {
            require(value > 0) { "route integer must be positive" }
            return PositiveIntArgument(value)
        }
    }
}

@JvmInline
public value class OpaqueKeyArgument private constructor(private val value: String) : SafeRouteArgument {
    override val kind: RouteArgumentKind
        get() = RouteArgumentKind.OPAQUE_KEY
    override val encoded: String
        get() = value

    internal companion object {
        fun validated(value: String): OpaqueKeyArgument {
            require(OPAQUE_KEY.matches(value)) { "opaque route key must be a non-sensitive allowlisted slug" }
            return OpaqueKeyArgument(value)
        }

        private val OPAQUE_KEY = Regex("[A-Za-z][A-Za-z0-9._-]{0,63}")
    }
}

@JvmInline
public value class NamedEnumArgument private constructor(private val value: String) : SafeRouteArgument {
    override val kind: RouteArgumentKind
        get() = RouteArgumentKind.NAMED_ENUM
    override val encoded: String
        get() = value

    internal companion object {
        fun validated(value: String): NamedEnumArgument {
            require(ENUM_NAME.matches(value)) { "named enum route value is invalid" }
            return NamedEnumArgument(value)
        }

        private val ENUM_NAME = Regex("[A-Z][A-Z0-9_]{0,47}")
    }
}

@JvmInline
public value class ContractEnumArgument private constructor(private val value: String) : SafeRouteArgument {
    override val kind: RouteArgumentKind
        get() = RouteArgumentKind.ENUM
    override val encoded: String
        get() = value

    internal companion object {
        fun validated(value: String): ContractEnumArgument = ContractEnumArgument(value)
    }
}

@Immutable
public class LedgerDestinationKey internal constructor(
    public val contract: ScreenContract,
    internal val argumentValues: Map<String, SafeRouteArgument>,
) : NavKey {
    public val path: String = contract.parameters.fold(contract.routePattern) { current, parameter ->
        val placeholder = "{${parameter.name}${if (parameter.optional) "?" else ""}}"
        val argument = argumentValues[parameter.name]
        if (argument == null) {
            current.replace("/$placeholder", "").replace(placeholder, "")
        } else {
            current.replace(placeholder, argument.encoded)
        }
    }

    override fun equals(other: Any?): Boolean = other is LedgerDestinationKey && contract.screenId == other.contract.screenId && path == other.path

    override fun hashCode(): Int = 31 * contract.screenId.hashCode() + path.hashCode()

    override fun toString(): String = "LedgerDestinationKey(screenId=${contract.screenId.value})"
}

public object LedgerRouteContract {
    public val allScreens: List<ScreenContract> = GeneratedScreenContract.screens

    private val byId: Map<ScreenId, ScreenContract> = allScreens.associateBy(ScreenContract::screenId)

    public fun screen(screenId: ScreenId): ScreenContract = requireNotNull(byId[screenId]) {
        "screen ID is not registered in the frozen contract"
    }

    public fun enumArgument(screenId: ScreenId, parameterName: String, value: String): ContractEnumArgument {
        val parameter = requireParameter(screenId, parameterName)
        require(parameter.kind == RouteArgumentKind.ENUM && value in parameter.allowedValues) {
            "enum route value is outside the frozen contract"
        }
        return ContractEnumArgument.validated(value)
    }

    public fun destination(
        screenId: ScreenId,
        arguments: Map<String, SafeRouteArgument> = emptyMap(),
    ): LedgerDestinationKey {
        val contract = screen(screenId)
        val specs = contract.parameters.associateBy(ScreenParameterSpec::name)
        require(arguments.keys == arguments.keys.intersect(specs.keys)) { "unknown route argument" }
        contract.parameters.forEach { parameter ->
            val argument = arguments[parameter.name]
            require(parameter.optional || argument != null) { "missing required route argument ${parameter.name}" }
            if (argument != null) {
                require(argument.kind == parameter.kind) { "route argument kind differs for ${parameter.name}" }
                if (parameter.kind == RouteArgumentKind.ENUM) {
                    require(argument.encoded in parameter.allowedValues) { "route enum differs from contract" }
                }
            }
        }
        return LedgerDestinationKey(contract, arguments.toMap())
    }

    private fun requireParameter(screenId: ScreenId, parameterName: String): ScreenParameterSpec {
        val parameter = screen(screenId).parameters.singleOrNull { it.name == parameterName }
        return requireNotNull(parameter) {
            "route parameter is not registered for screen"
        }
    }
}
