package registry

enum class ErrorKind {
    VALIDATION,
    NOT_FOUND,
    CONFLICT,
    SELF_REVIEW,
    DUPLICATE_CONFIRMATION,
    INTERVAL_CLOSED,
    WRONG_STATE,
    CHAIN_CONFLICT,
    BLOB_MISSING,
}

class ApiError(val kind: ErrorKind, message: String, val auditType: String? = null) : RuntimeException(message)
