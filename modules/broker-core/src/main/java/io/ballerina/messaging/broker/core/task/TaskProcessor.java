/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */

package io.ballerina.messaging.broker.core.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Process {@link Task} by going through each {@link TaskHolder} in the {@link TaskHolder} ring.
 */
final class TaskProcessor implements Callable<Boolean> {

    /**
     * Logger.
     */
    private static Logger log = LoggerFactory.getLogger(TaskProcessor.class);

    /**
     * Reference to {@link TaskHolder} queue.
     */
    private DelayQueue<TaskHolder> taskHolderQueue;

    /**
     * Whether the processor is active or not.
     */
    private AtomicBoolean isActive;

    /**
     * Reference to the exception handler of the queue.
     */
    private TaskExceptionHandler taskExceptionHandler;

    /**
     * Delay to be set for the IDLE tasks.
     */
    private final long idleWaitTimeMillis;

    TaskProcessor(DelayQueue<TaskHolder> taskQueue, TaskExceptionHandler exceptionHandler, long idleWaitTimeMillis) {
        isActive = new AtomicBoolean(false);
        this.taskExceptionHandler = exceptionHandler;
        this.taskHolderQueue = taskQueue;
        this.idleWaitTimeMillis = idleWaitTimeMillis;
    }

    /**
     * Deactivate a task for removal.
     */
    void deactivate() {
        isActive.set(false);
    }

    @Override
    public Boolean call() {

        if (!isActive.compareAndSet(false, true)) {
            log.error("Task processor is already running ");
            throw new IllegalStateException("Task processor is already running");
        }
        log.debug("Task processor started");
        while (isActive.get()) {
            TaskHolder taskHolder = null;
            long delay = 0; // No delay
            try {
                taskHolder = taskHolderQueue.take(); // Wait if queue is empty
                Task.TaskHint hint = taskHolder.executeTask();
                if (hint == Task.TaskHint.IDLE) {
                    delay = idleWaitTimeMillis;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (isActive.get()) {
                    taskExceptionHandler.handleException(e, "null");
                }
            } catch (Throwable throwable) {
                String id = Objects.nonNull(taskHolder) ? taskHolder.getId() : "null";
                taskExceptionHandler.handleException(throwable, id);
            } finally {
                addToTaskQueueIfActive(taskHolder, delay);
            }
        }
        log.info("Task processor stopped. Task queue size {}", taskHolderQueue.size());

        return true;
    }

    private void addToTaskQueueIfActive(TaskHolder taskHolder, long delay) {
        // Disabled Tasks will get removed from the queue. Put back to queue if not disabled.
        if (Objects.nonNull(taskHolder)) {
            if (taskHolder.isDisabled()) {
                taskHolder.onRemoveTask();
            } else {
                // Add a delay when adding back to the queue. This ensures the TaskHolder is added to the
                // end of the queue. If not TaskHolder will be added to front of the queue
                taskHolder.setDelay(delay, TimeUnit.MILLISECONDS);
                taskHolderQueue.put(taskHolder);
            }
        }
    }
}
