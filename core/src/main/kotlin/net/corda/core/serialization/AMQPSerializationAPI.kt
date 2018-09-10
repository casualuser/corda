@file:KeepForDJVM
package net.corda.core.serialization

import net.corda.core.DeleteForDJVM
import net.corda.core.DoNotImplement
import net.corda.core.KeepForDJVM
import net.corda.core.crypto.SecureHash
import net.corda.core.serialization.internal.effectiveAMQPSerializationEnv
import net.corda.core.utilities.ByteSequence
import net.corda.core.utilities.sequence

data class ObjectWithCompatibleAMQPContext<out T : Any>(val obj: T, val context: AMQPSerializationContext)

abstract class AMQPSerializationFactory {

    companion object {
        private val _currentFactory = ThreadLocal<AMQPSerializationFactory?>()

        /**
         * A default factory for serialization/deserialization, taking into account the [currentFactory] if set.
         */
        val defaultFactory: AMQPSerializationFactory get() = currentFactory ?: effectiveAMQPSerializationEnv.serializationFactory

        /**
         * If there is a need to nest serialization/deserialization with a modified context during serialization or deserialization,
         * this will return the current factory used to start serialization/deserialization.
         */
        val currentFactory: AMQPSerializationFactory? get() = _currentFactory.get()
    }

    /**
     * Deserialize the bytes in to an object, using the prefixed bytes to determine the format.
     *
     * @param byteSequence The bytes to deserialize, including a format header prefix.
     * @param clazz The class or superclass or the object to be deserialized, or [Any] or [Object] if unknown.
     * @param context A context that configures various parameters to deserialization.
     */
    abstract fun <T : Any> deserialize(byteSequence: ByteSequence, clazz: Class<T>, context: AMQPSerializationContext): T

    /**
     * Deserialize the bytes in to an object, using the prefixed bytes to determine the format.
     *
     * @param byteSequence The bytes to deserialize, including a format header prefix.
     * @param clazz The class or superclass or the object to be deserialized, or [Any] or [Object] if unknown.
     * @param context A context that configures various parameters to deserialization.
     * @return deserialized object along with [SerializationContext] to identify encoding used.
     */
    fun <T : Any> deserializeWithCompatibleContext(byteSequence: ByteSequence, clazz: Class<T>, context: AMQPSerializationContext) =
            ObjectWithCompatibleAMQPContext(deserialize(byteSequence, clazz, context), context)

    /**
     * Serialize an object to bytes using the preferred serialization format version from the context.
     *
     * @param obj The object to be serialized.
     * @param context A context that configures various parameters to serialization, including the serialization format version.
     */
    abstract fun <T : Any> serialize(obj: T, context: AMQPSerializationContext): SerializedBytes<T>


    /**
     * Allow subclasses to temporarily mark themselves as the current factory for the current thread during serialization/deserialization.
     * Will restore the prior context on exiting the block.
     */
    fun <T> asCurrent(block: AMQPSerializationFactory.() -> T): T {
        val priorContext = _currentFactory.get()
        _currentFactory.set(this)
        try {
            return this.block()
        } finally {
            _currentFactory.set(priorContext)
        }
    }

    /**
     * If there is a need to nest serialization/deserialization with a modified context during serialization or deserialization,
     * this will return the current context used to start serialization/deserialization.
     */
    val currentContext: AMQPSerializationContext? get() = _currentContext.get()

    /**
     * A context to use as a default if you do not require a specially configured context.  It will be the current context
     * if the use is somehow nested (see [currentContext]).
     */
    val defaultContext: AMQPSerializationContext get() = currentContext ?: effectiveAMQPSerializationEnv.p2pContext

    private val _currentContext = ThreadLocal<AMQPSerializationContext?>()

    /**
     * Change the current context inside the block to that supplied.
     */
    fun <T> withCurrentContext(context: AMQPSerializationContext?, block: () -> T): T {
        val priorContext = _currentContext.get()
        if (context != null) _currentContext.set(context)
        try {
            return block()
        } finally {
            if (context != null) _currentContext.set(priorContext)
        }
    }
}

@DoNotImplement
interface AMQPSerializationEncoding

/**
 * Parameters to serialization and deserialization.
 */
@KeepForDJVM
@DoNotImplement
interface AMQPSerializationContext {
    /**
     * If non-null, apply this encoding (typically compression) when serializing.
     */
    val encoding: AMQPSerializationEncoding?
    /**
     * The class loader to use for deserialization.
     */
    val deserializationClassLoader: ClassLoader
    /**
     * A whitelist that contains (mostly for security purposes) which classes can be serialized and deserialized.
     */
    val whitelist: ClassWhitelist
    /**
     * A whitelist that determines (mostly for security purposes) whether a particular encoding may be used when deserializing.
     */
    val encodingWhitelist: EncodingWhitelist
    /**
     * A map of any addition properties specific to the particular use case.
     */
    val properties: Map<Any, Any>
    /**
     * Duplicate references to the same object preserved in the wire format and when deserialized when this is true,
     * otherwise they appear as new copies of the object.
     */
    val objectReferencesEnabled: Boolean
    /**
     * If true the carpenter will happily synthesis classes that implement interfaces containing methods that are not
     * getters for any AMQP fields. Invoking these methods will throw an [AbstractMethodError]. If false then an exception
     * will be thrown during deserialization instead.
     *
     * The default is false.
     */
    val lenientCarpenterEnabled: Boolean
    /**
     * The use case we are serializing or deserializing for.  See [UseCase].
     */
    val useCase: UseCase

    /**
     * Helper method to return a new context based on this context with the property added.
     */
    fun withProperty(property: Any, value: Any): AMQPSerializationContext

    /**
     * Helper method to return a new context based on this context with object references disabled.
     */
    fun withoutReferences(): AMQPSerializationContext

    /**
     * Return a new context based on this one but with a lenient carpenter.
     * @see lenientCarpenterEnabled
     */
    fun withLenientCarpenter(): AMQPSerializationContext

    /**
     * Helper method to return a new context based on this context with the deserialization class loader changed.
     */
    fun withClassLoader(classLoader: ClassLoader): AMQPSerializationContext

    /**
     * Helper method to return a new context based on this context with the appropriate class loader constructed from the passed attachment identifiers.
     * (Requires the attachment storage to have been enabled).
     */
    @Throws(MissingAttachmentsException::class)
    fun withAttachmentsClassLoader(attachmentHashes: List<SecureHash>): AMQPSerializationContext

    /**
     * Helper method to return a new context based on this context with the given class specifically whitelisted.
     */
    fun withWhitelisted(clazz: Class<*>): AMQPSerializationContext

    /**
     * A shallow copy of this context but with the given (possibly null) encoding.
     */
    fun withEncoding(encoding: AMQPSerializationEncoding?): AMQPSerializationContext

    /**
     * A shallow copy of this context but with the given encoding whitelist.
     */
    fun withEncodingWhitelist(encodingWhitelist: EncodingWhitelist): AMQPSerializationContext

    /**
     * The use case that we are serializing for, since it influences the implementations chosen.
     */
    @KeepForDJVM
    enum class UseCase { P2P, RPCServer, RPCClient, Storage, Testing }
}

/**
 * Global singletons to be used as defaults that are injected elsewhere (generally, in the node or in RPC client).
 */
@KeepForDJVM
object AMQPSerializationDefaults {
    val SERIALIZATION_FACTORY get() = effectiveAMQPSerializationEnv.serializationFactory
    val P2P_CONTEXT get() = effectiveAMQPSerializationEnv.p2pContext
    @DeleteForDJVM val RPC_SERVER_CONTEXT get() = effectiveAMQPSerializationEnv.rpcServerContext
    @DeleteForDJVM val RPC_CLIENT_CONTEXT get() = effectiveAMQPSerializationEnv.rpcClientContext
    @DeleteForDJVM val STORAGE_CONTEXT get() = effectiveAMQPSerializationEnv.storageContext
}


/**
 * Convenience extension method for deserializing a ByteSequence, utilising the default factory.
 */
inline fun <reified T : Any> ByteSequence.deserialize(serializationFactory: AMQPSerializationFactory = AMQPSerializationFactory.defaultFactory,
                                                      context: AMQPSerializationContext): T {
    return serializationFactory.deserialize(this, T::class.java, context)
}

/**
 * Convenience extension method for deserializing a ByteSequence, utilising the defaults.
 */
inline fun <reified T : Any> ByteSequence.deserialize() =
        deserialize<T>(context = AMQPSerializationFactory.defaultFactory.defaultContext)

/**
 * Convenience extension method for deserializing SerializedBytes with type matching, utilising the default factory.
 */
inline fun <reified T : Any> SerializedBytes<T>.deserialize(serializationFactory: AMQPSerializationFactory = AMQPSerializationFactory.defaultFactory,
                                                            context: AMQPSerializationContext): T {
    return serializationFactory.deserialize(this, T::class.java, context)
}

/**
 * Convenience extension method for deserializing SerializedBytes with type matching, utilising the defaults.
 */
inline fun <reified T : Any> SerializedBytes<T>.deserialize(): T =
        deserialize(context = AMQPSerializationFactory.defaultFactory.defaultContext)


/**
 * Convenience extension method for deserializing a ByteArray, utilising the default factory.
 */
inline fun <reified T : Any> ByteArray.deserialize(serializationFactory: AMQPSerializationFactory = AMQPSerializationFactory.defaultFactory,
                                                   context: AMQPSerializationContext): T {
    require(isNotEmpty()) { "Empty bytes" }
    return this.sequence().deserialize(serializationFactory, context)
}

/**
 * Convenience extension method for deserializing a ByteArray, utilising the defaults.
 */
inline fun <reified T : Any> ByteArray.deserialize(): T =
        deserialize(context = AMQPSerializationFactory.defaultFactory.defaultContext)


/**
 * Convenience extension method for serializing an object of type T, utilising the default factory.
 */
fun <T : Any> T.serialize(serializationFactory: AMQPSerializationFactory = AMQPSerializationFactory.defaultFactory,
                          context: AMQPSerializationContext): SerializedBytes<T> {
    return serializationFactory.serialize(this, context)
}

/**
 * Convenience extension method for serializing an object of type T, utilising the defaults.
 */
fun <T : Any> T.serialize(): SerializedBytes<T> {
    return serialize(context = AMQPSerializationFactory.defaultFactory.defaultContext)
}