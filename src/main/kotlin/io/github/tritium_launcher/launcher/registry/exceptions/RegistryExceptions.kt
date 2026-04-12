package io.github.tritium_launcher.launcher.registry.exceptions

/** Thrown when an id is registered more than once in the same registry. */
class DuplicateRegistrationException(msg: String): RuntimeException(msg)
/** Thrown when mutating a registry that has been frozen. */
class RegistryFrozenException(msg: String): RuntimeException(msg)
/** Thrown when a registry id does not match the expected pattern. */
class InvalidIdException(msg: String): RuntimeException(msg)
