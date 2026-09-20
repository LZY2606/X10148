package registry

open class RegistryException(message: String) : RuntimeException(message)

class ValidationException(message: String) : RegistryException(message)

class NotFoundException(message: String) : RegistryException(message)

class ConflictException(
    message: String,
    val expectedRevision: Int,
    val actualRevision: Int
) : RegistryException(message)
