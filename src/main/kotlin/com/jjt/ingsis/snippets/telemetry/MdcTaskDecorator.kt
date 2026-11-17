package com.jjt.ingsis.snippets.telemetry

import org.slf4j.MDC
import org.springframework.core.task.TaskDecorator
import org.springframework.stereotype.Component

@Component
class MdcTaskDecorator : TaskDecorator {
    override fun decorate(runnable: Runnable): Runnable {
        val context = MDC.getCopyOfContextMap().orEmpty()
        return Runnable {
            val previous = MDC.getCopyOfContextMap()
            MDC.setContextMap(context)
            try {
                runnable.run()
            } finally {
                if (previous == null) MDC.clear() else MDC.setContextMap(previous)
            }
        }
    }
}
