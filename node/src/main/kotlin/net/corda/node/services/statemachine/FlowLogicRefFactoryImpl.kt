package net.corda.node.services.statemachine

import com.google.common.annotations.VisibleForTesting
import com.google.common.primitives.Primitives
import net.corda.core.flows.*
import net.corda.core.serialization.CordaSerializable
import net.corda.core.serialization.SingletonSerializeAsToken
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.util.*
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.jvm.javaConstructor
import kotlin.reflect.jvm.javaType

/**
 * The internal concrete implementation of the FlowLogicRef marker interface.
 */
@CordaSerializable
data class FlowLogicRefImpl internal constructor(val flowLogicClassName: String, val appContext: AppContext, val args: Map<String, Any?>) : FlowLogicRef

/**
 * A class for conversion to and from [FlowLogic] and [FlowLogicRef] instances.
 *
 * Validation of types is performed on the way in and way out in case this object is passed between JVMs which might have differing
 * whitelists.
 *
 * TODO: Ways to populate whitelist of "blessed" flows per node/party
 * TODO: Ways to populate argument types whitelist. Per node/party or global?
 * TODO: Align with API related logic for passing in FlowLogic references (FlowRef)
 * TODO: Actual support for AppContext / AttachmentsClassLoader
 */
object FlowLogicRefFactoryImpl : SingletonSerializeAsToken(), FlowLogicRefFactory {
    /**
     * Create a [FlowLogicRef] by assuming a single constructor and the given args.
     */
    override fun create(type: Class<out FlowLogic<*>>, vararg args: Any?): FlowLogicRef {
        // XXX Add seperate method for checking flow is schedulable annotation
        // XXX Test that
        // TODO: This is used via RPC but it's probably better if we pass in argument names and values explicitly
        // to avoid requiring only a single constructor.
        val argTypes = args.map { it?.javaClass }
        val constructor = try {
            type.kotlin.constructors.single { ctor ->
                // Get the types of the arguments, always boxed (as that's what we get in the invocation).
                val ctorTypes = ctor.javaConstructor!!.parameterTypes.map { Primitives.wrap(it) }
                if (argTypes.size != ctorTypes.size)
                    return@single false
                for ((argType, ctorType) in argTypes.zip(ctorTypes)) {
                    if (argType == null) continue   // Try and find a match based on the other arguments.
                    if (!ctorType.isAssignableFrom(argType)) return@single false
                }
                true
            }
        } catch (e: IllegalArgumentException) {
            throw IllegalFlowLogicException(type, "due to ambiguous match against the constructors: $argTypes")
        } catch (e: NoSuchElementException) {
            throw IllegalFlowLogicException(type, "due to missing constructor for arguments: $argTypes")
        }

        // Build map of args from array
        val argsMap = args.zip(constructor.parameters).map { Pair(it.second.name!!, it.first) }.toMap()
        return createKotlin(type, argsMap)
    }

    /**
     * Create a [FlowLogicRef] by trying to find a Kotlin constructor that matches the given args.
     *
     * TODO: Rethink language specific naming.
     */
    @VisibleForTesting
    internal fun createKotlin(type: Class<out FlowLogic<*>>, args: Map<String, Any?>): FlowLogicRef {
        // TODO: we need to capture something about the class loader or "application context" into the ref,
        //       perhaps as some sort of ThreadLocal style object.  For now, just create an empty one.
        val appContext = AppContext(emptyList())
        // Check we can find a constructor and populate the args to it, but don't call it
        createConstructor(type, args)
        return FlowLogicRefImpl(type.name, appContext, args)
    }

    fun toFlowLogic(ref: FlowLogicRef): FlowLogic<*> {
        if (ref !is FlowLogicRefImpl) throw IllegalFlowLogicException(ref.javaClass, "FlowLogicRef was not created via correct FlowLogicRefFactory interface")
        val klass = Class.forName(ref.flowLogicClassName, true, ref.appContext.classLoader).asSubclass(FlowLogic::class.java)
        return createConstructor(klass, ref.args)()
    }

    private fun createConstructor(clazz: Class<out FlowLogic<*>>, args: Map<String, Any?>): () -> FlowLogic<*> {
        for (constructor in clazz.kotlin.constructors) {
            val params = buildParams(constructor, args) ?: continue
            // If we get here then we matched every parameter
            return { constructor.callBy(params) }
        }
        throw IllegalFlowLogicException(clazz, "as could not find matching constructor for: $args")
    }

    private fun buildParams(constructor: KFunction<FlowLogic<*>>, args: Map<String, Any?>): HashMap<KParameter, Any?>? {
        val params = hashMapOf<KParameter, Any?>()
        val usedKeys = hashSetOf<String>()
        for (parameter in constructor.parameters) {
            if (!tryBuildParam(args, parameter, params)) {
                return null
            } else {
                usedKeys += parameter.name!!
            }
        }
        if ((args.keys - usedKeys).isNotEmpty()) {
            // Not all args were used
            return null
        }
        return params
    }

    private fun tryBuildParam(args: Map<String, Any?>, parameter: KParameter, params: HashMap<KParameter, Any?>): Boolean {
        val containsKey = parameter.name in args
        // OK to be missing if optional
        return (parameter.isOptional && !containsKey) || (containsKey && paramCanBeBuilt(args, parameter, params))
    }

    private fun paramCanBeBuilt(args: Map<String, Any?>, parameter: KParameter, params: HashMap<KParameter, Any?>): Boolean {
        val value = args[parameter.name]
        params[parameter] = value
        return (value is Any && parameterAssignableFrom(parameter.type.javaType, value)) || parameter.type.isMarkedNullable
    }

    private fun parameterAssignableFrom(type: Type, value: Any): Boolean {
        if (type is Class<*>) {
            if (type.isPrimitive) {
                return Primitives.unwrap(value.javaClass) == type
            } else {
                return type.isAssignableFrom(value.javaClass)
            }
        } else if (type is ParameterizedType) {
            return parameterAssignableFrom(type.rawType, value)
        } else {
            return false
        }
    }
}