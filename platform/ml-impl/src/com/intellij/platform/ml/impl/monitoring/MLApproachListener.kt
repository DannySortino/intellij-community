// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.ml.impl.monitoring

import com.intellij.platform.ml.Environment
import com.intellij.platform.ml.Session
import com.intellij.platform.ml.impl.MLTaskApproach
import com.intellij.platform.ml.impl.MLTaskApproachBuilder
import com.intellij.platform.ml.impl.apiPlatform.MLApiPlatform
import com.intellij.platform.ml.impl.apiPlatform.MLTaskGroupListenerProvider
import com.intellij.platform.ml.impl.model.MLModel
import com.intellij.platform.ml.impl.monitoring.MLApproachInitializationListener.Companion.asJoinedListener
import com.intellij.platform.ml.impl.monitoring.MLApproachListener.Companion.asJoinedListener
import com.intellij.platform.ml.impl.monitoring.MLSessionListener.Companion.asJoinedListener
import com.intellij.platform.ml.impl.monitoring.MLTaskGroupListener.ApproachListeners.Companion.monitoredBy
import com.intellij.platform.ml.impl.session.DescribedRootContainer
import org.jetbrains.annotations.ApiStatus

/**
 * Provides listeners for a set of [com.intellij.platform.ml.impl.MLTaskApproach]
 *
 * Only [com.intellij.platform.ml.impl.LogDrivenModelInference] and the subclasses, that are
 * calling [com.intellij.platform.ml.impl.LogDrivenModelInference.startSession] are monitored.
 */
@ApiStatus.Internal
interface MLTaskGroupListener {
  /**
   * For every approach, the [MLTaskGroupListener] is interested in this value provides a collection of
   * [MLApproachInitializationListener]
   *
   * The comfortable way to create this accordance would be by using
   * [com.intellij.platform.ml.impl.monitoring.MLTaskGroupListener.ApproachListeners.Companion.monitoredBy] infix function.
   */
  val approachListeners: Collection<ApproachListeners<*, *>>

  /**
   * A type-safe pair of approach's class and a set of listeners
   *
   * A proper way to create it is to use [monitoredBy]
   */
  data class ApproachListeners<M : MLModel<P>, P : Any> internal constructor(
    val taskApproachBuilder: Class<out MLTaskApproachBuilder<P>>,
    val approachListener: Collection<MLApproachInitializationListener<M, P>>
  ) {
    companion object {
      infix fun <M : MLModel<P>, P : Any> Class<out MLTaskApproachBuilder<P>>.monitoredBy(approachListener: MLApproachInitializationListener<M, P>) = ApproachListeners(
        this, listOf(approachListener))

      infix fun <M : MLModel<P>, P : Any> Class<out MLTaskApproachBuilder<P>>.monitoredBy(approachListeners: Collection<MLApproachInitializationListener<M, P>>) = ApproachListeners(
        this, approachListeners)
    }
  }

  @ApiStatus.Internal
  interface Default : MLTaskGroupListener, MLTaskGroupListenerProvider {
    override fun provide(collector: (MLTaskGroupListener) -> Unit) {
      return collector(this)
    }
  }

  companion object {
    internal val MLTaskGroupListener.targetedApproaches: Set<Class<out MLTaskApproachBuilder<*>>>
      get() = approachListeners.map { it.taskApproachBuilder }.toSet()

    internal fun <P : Any, M : MLModel<P>> MLTaskGroupListener.onAttemptedToStartSession(taskApproachBuilder: MLTaskApproachBuilder<P>,
                                                                                         apiPlatform: MLApiPlatform,
                                                                                         callEnvironment: Environment,
                                                                                         permanentSessionEnvironment: Environment): MLApproachListener<M, P>? {
      @Suppress("UNCHECKED_CAST")
      val approachListeners: List<MLApproachInitializationListener<M, P>> = approachListeners
        .filter { it.taskApproachBuilder == taskApproachBuilder.javaClass }
        .flatMap { it.approachListener } as List<MLApproachInitializationListener<M, P>>

      return approachListeners.asJoinedListener().onAttemptedToStartSession(apiPlatform, permanentSessionEnvironment, callEnvironment)
    }
  }
}

/**
 * Listens to the attempt of starting new [Session] of the [MLTaskApproach], that this listener was put
 * into correspondence to via [com.intellij.platform.ml.impl.monitoring.MLTaskGroupListener.ApproachListeners.Companion.monitoredBy]
 *
 * @param M Type of the [com.intellij.platform.ml.impl.model.MLModel]
 * @param P Prediction's type
 */
@ApiStatus.Internal
fun interface MLApproachInitializationListener<M : MLModel<P>, P : Any> {
  /**
   * Called each time, when [com.intellij.platform.ml.impl.LogDrivenModelInference.startSession] is invoked
   *
   * @return A listener, that will be monitoring how successful the start was. If it is not needed, null is returned.
   */
  fun onAttemptedToStartSession(apiPlatform: MLApiPlatform, permanentSessionEnvironment: Environment, callParameters: Environment): MLApproachListener<M, P>?

  companion object {
    fun <M : MLModel<P>, P : Any> Collection<MLApproachInitializationListener<M, P>>.asJoinedListener(): MLApproachInitializationListener<M, P> =
      MLApproachInitializationListener { apiPlatform, callEnvironment, permanentSessionEnvironment ->
        val approachListeners = this@asJoinedListener.mapNotNull { it.onAttemptedToStartSession(apiPlatform, permanentSessionEnvironment, callEnvironment) }
        if (approachListeners.isEmpty()) null else approachListeners.asJoinedListener()
      }
  }
}

/**
 * Listens to the process of starting new [Session] of [com.intellij.platform.ml.impl.LogDrivenModelInference].
 */
@ApiStatus.Internal
interface MLApproachListener<M : MLModel<P>, P : Any> {
  /**
   * Called if the session was not started,
   * on exceptionally rare occasions,
   * when the [com.intellij.platform.ml.impl.LogDrivenModelInference.startSession] failed with an exception
   */
  fun onFailedToStartSessionWithException(exception: Throwable) {}

  /**
   * Called if the session was not started,
   * but the failure is 'ordinary'.
   */
  fun onFailedToStartSession(failure: Session.StartOutcome.Failure<P>) {}

  /**
   * Called when a new [com.intellij.platform.ml.impl.LogDrivenModelInference]'s session was started successfully.
   *
   * @return A listener for tracking the session's progress, null if the session will not be tracked.
   */
  fun onStartedSession(session: Session<P>, mlModel: M): MLSessionListener<M, P>? = null

  companion object {
    fun <M : MLModel<P>, P : Any> Collection<MLApproachListener<M, P>>.asJoinedListener(): MLApproachListener<M, P> {
      val approachListeners = this@asJoinedListener

      return object : MLApproachListener<M, P> {
        override fun onFailedToStartSessionWithException(exception: Throwable) =
          approachListeners.forEach { it.onFailedToStartSessionWithException(exception) }

        override fun onFailedToStartSession(failure: Session.StartOutcome.Failure<P>) = approachListeners.forEach {
          it.onFailedToStartSession(failure)
        }

        override fun onStartedSession(session: Session<P>, mlModel: M): MLSessionListener<M, P>? {
          val listeners = approachListeners.mapNotNull { it.onStartedSession(session, mlModel) }
          return if (listeners.isEmpty()) null else listeners.asJoinedListener()
        }
      }
    }
  }
}

/**
 * Listens to session events of a [com.intellij.platform.ml.impl.LogDrivenModelInference]
 */
@ApiStatus.Internal
interface MLSessionListener<R, P : Any> {
  /**
   * All tier instances were established (the tree will not be growing further),
   * described, and predictions in the [sessionTree] were finished.
   */
  fun onSessionDescriptionFinished(sessionTree: DescribedRootContainer<R, P>) {}

  companion object {
    fun <R, P : Any> Collection<MLSessionListener<R, P>>.asJoinedListener(): MLSessionListener<R, P> {
      val sessionListeners = this@asJoinedListener

      return object : MLSessionListener<R, P> {
        override fun onSessionDescriptionFinished(sessionTree: DescribedRootContainer<R, P>) = sessionListeners.forEach {
          it.onSessionDescriptionFinished(sessionTree)
        }
      }
    }
  }
}
