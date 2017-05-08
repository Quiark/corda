package net.corda.core.flows

import java.lang.annotation.Inherited
import kotlin.annotation.AnnotationTarget.CLASS

/**
 *
 */
@Target(CLASS)
@Inherited
@MustBeDocumented
annotation class StartableByRPC