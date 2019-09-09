package rundeck.services


import com.dtolabs.rundeck.core.common.PluginControlService
import com.dtolabs.rundeck.core.execution.ExecutionContextImpl
import com.dtolabs.rundeck.core.execution.ExecutionReference
import com.dtolabs.rundeck.core.execution.ExecutionLifecyclePluginException
import com.dtolabs.rundeck.core.execution.workflow.StepExecutionContext
import com.dtolabs.rundeck.core.jobs.IExecutionLifecyclePluginService
import com.dtolabs.rundeck.core.jobs.JobEventStatus
import com.dtolabs.rundeck.core.jobs.JobOption
import com.dtolabs.rundeck.core.jobs.ExecutionLifecyclePluginHandler
import com.dtolabs.rundeck.core.plugins.DescribedPlugin
import com.dtolabs.rundeck.core.plugins.PluginConfigSet
import com.dtolabs.rundeck.core.plugins.PluginProviderConfiguration
import com.dtolabs.rundeck.core.plugins.SimplePluginConfiguration
import com.dtolabs.rundeck.plugins.ServiceNameConstants
import com.dtolabs.rundeck.plugins.jobs.ExecutionLifecyclePlugin
import com.dtolabs.rundeck.plugins.jobs.JobExecutionEventImpl

import com.dtolabs.rundeck.server.plugins.services.ExecutionLifecyclePluginProviderService
import rundeck.ScheduledExecution

/**
 * Provides capability to execute certain task based on a job event
 * Created by rnavarro
 * Date: 5/07/19
 * Time: 10:32 AM
 */

class ExecutionLifecyclePluginService implements IExecutionLifecyclePluginService {

    PluginService pluginService
    ExecutionLifecyclePluginProviderService executionLifecyclePluginProviderService
    FrameworkService frameworkService
    def featureService

    enum EventType{
        BEFORE_RUN('beforeJobRun'), AFTER_RUN('afterJobRun')
        private final String value
        EventType(String value){
            this.value = value
        }
        String getValue(){
            this.value
        }
    }

    /**
     *
     * @return Map containing all of the ExecutionLifecyclePlugin implementations
     */
    Map listExecutionLifecyclePlugins(){
        if(!featureService?.featurePresent('executionLifecycle-plugin', false)){
            return pluginService?.listPlugins(ExecutionLifecyclePlugin, executionLifecyclePluginProviderService)
        }
        return null
    }

    /**
     *
     * @param event job event
     * @param eventType type of event
     * @return JobEventStatus response from plugin implementation
     */
    JobEventStatus handleEvent(def event, EventType eventType, List<NamedExecutionLifecyclePlugin> plugins) {
        if (!plugins) {
            return null
        }
        def errors = [:]
        def results = [:]
        Exception firstErr
        JobEventStatus prevResult = null
        def prevEvent = event
        boolean success = true
        for (NamedExecutionLifecyclePlugin plugin : plugins) {
            try {

                def curEvent = mergeEvent(prevResult, prevEvent)
                JobEventStatus result = handleEventForPlugin(eventType, plugin, curEvent)
                if (result != null && !result.successful) {
                    success = false
                    log.info("Result from plugin is false an exception will be thrown")
                    if (result.getDescription() != null && !result.getDescription().trim().isEmpty()) {
                        throw new ExecutionLifecyclePluginException(result.getDescription())
                    } else {
                        throw new ExecutionLifecyclePluginException(
                                "Response from $plugin.name is false, but no description was provided by the plugin"
                        )
                    }

                }
                if (result != null && result.useNewValues()) {
                    results[plugin.name] = result
                    prevResult = result
                }
                prevEvent = curEvent
            } catch (Exception e) {
                success = false
                if (!firstErr) {
                    firstErr = e
                }
                errors[plugin.name] = e
            }
        }
        if (errors) {
            errors.each { name, Exception e ->
                log.error("Error (ExecutionLifecyclePlugin:$name/$eventType): $e.message", e)
            }
            if (firstErr) {
                throw firstErr
            }
        }

        mergeEventResult(success, prevResult, prevEvent, !results.isEmpty())

    }

    JobEventStatus handleEventForPlugin(
            EventType eventType,
            NamedExecutionLifecyclePlugin plugin,
            event
    ) {
        switch (eventType) {
            case EventType.BEFORE_RUN:
                return plugin.beforeJobStarts(event)
            case EventType.AFTER_RUN:
                return plugin.afterJobEnds(event)
        }
    }

    /**
     * Merge
     * @param jobEventStatus
     * @param jobEvent
     * @return
     */
    Object mergeEvent(final JobEventStatus jobEventStatus, final Object jobEvent) {
        if (jobEvent instanceof JobExecutionEventImpl) {
            ExecutionContextImpl newContext = mergeExecutionEventContext(
                    jobEvent.executionContext,
                    jobEventStatus
            )

            return jobEvent.result != null ?
                   JobExecutionEventImpl.afterRun(newContext, jobEvent.execution, jobEvent.result) :
                   JobExecutionEventImpl.beforeRun(newContext, jobEvent.execution, jobEvent.workflow)
        } else {
            throw new IllegalArgumentException("Unexpected type")
        }
    }


    /**
     * Merge original event, event status result, return a new result
     * @param success overall success
     * @param jobEventStatus result of plugin handling event
     * @param jobEvent event
     * @return result with merged contents for the type of event
     */
    JobEventStatus mergeEventResult(boolean success, final JobEventStatus jobEventStatus, final Object jobEvent, boolean useNewValues) {
        if (jobEvent instanceof JobExecutionEventImpl) {
            ExecutionContextImpl newContext = mergeExecutionEventContext(jobEvent.executionContext, jobEventStatus)

            return new JobEventStatusImpl(successful: success, executionContext: newContext)
        } else {
            throw new IllegalArgumentException("Unexpected type")
        }
    }

    /**
     * Merge the context with result of event
     * @param context original
     * @param jobEventStatus result of event
     * @return merged context if the event has an executionContext value and useNewValues is true
     */
    ExecutionContextImpl mergeExecutionEventContext(
            StepExecutionContext context,
            JobEventStatus jobEventStatus
    ) {
        def newContextBuilder = ExecutionContextImpl.builder(context)
        if (jobEventStatus && jobEventStatus.useNewValues() && jobEventStatus.executionContext) {
            newContextBuilder.merge(ExecutionContextImpl.builder(jobEventStatus.executionContext))
        }
        newContextBuilder.build()
    }

    /**
     * Load configured ExecutionLifecyclePlugin instances for the job
     * @param configurations
     * @param project
     * @return
     */
    List<NamedExecutionLifecyclePlugin> createConfiguredPlugins(PluginConfigSet configurations, String project) {
        List<NamedExecutionLifecyclePlugin> configured = []

        configurations?.pluginProviderConfigs?.each { PluginProviderConfiguration pluginConfig ->
            String type = pluginConfig.provider
            def configuredPlugin = pluginService.configurePlugin(
                    type,
                    pluginConfig.configuration,
                    project,
                    frameworkService.rundeckFramework,
                    ExecutionLifecyclePlugin
            )
            if (!configuredPlugin) {
                //TODO: could not load plugin, or config was invalid
                return
            }
            configured << new NamedExecutionLifecyclePlugin(plugin: (ExecutionLifecyclePlugin) configuredPlugin.instance, name: type)
        }
        configured
    }


    /**
     *
     * @param project
     * @return map of described plugins enabled for the project
     */
    Map<String, DescribedPlugin<ExecutionLifecyclePlugin>> listEnabledExecutionLifecyclePlugins(
            PluginControlService pluginControlService
    ) {
        if (!featureService.featurePresent('executionLifecycle-plugin', false)) {
            return null
        }

        return pluginService.listPlugins(ExecutionLifecyclePlugin).findAll { k, v ->
            !pluginControlService?.isDisabledPlugin(k, ServiceNameConstants.ExecutionLifecyclePlugin)
        }
    }

    /**
     * Read the config set for the job
     * @param job
     * @return PluginConfigSet for the ExecutionLifecyclePlugin service for the job, or null if not defined or not enabled
     */
    PluginConfigSet getExecutionLifecyclePluginConfigSetForJob(ScheduledExecution job) {
        if (!featureService?.featurePresent('executionLifecycle-plugin', false)) {
            return null
        }
        def pluginConfig = job.pluginConfigMap?.get ServiceNameConstants.ExecutionLifecyclePlugin

        if (!(pluginConfig instanceof Map)) {
            return null
        }
        List<PluginProviderConfiguration> configs = []
        pluginConfig.each { String type, Map config ->
            configs << SimplePluginConfiguration.builder().provider(type).configuration(config).build()
        }

        PluginConfigSet.with ServiceNameConstants.ExecutionLifecyclePlugin, configs
    }

    /**
     * Store the plugin config set for the job
     * @param job job
     * @param configSet config set
     */
    def setExecutionLifecyclePluginConfigSetForJob(final ScheduledExecution job, final PluginConfigSet configSet) {
        Map<String, Map<String, Object>> data = configSet.pluginProviderConfigs.collectEntries {
            [it.provider, it.configuration]
        }
        job.setPluginConfigVal(ServiceNameConstants.ExecutionLifecyclePlugin, data)
    }


    /**
     * Create handler for execution ref and plugin configuration
     *
     * @param configurations configurations
     * @param executionReference reference
     * @return execution event handler
     */
    ExecutionLifecyclePluginHandler getExecutionHandler(PluginConfigSet configurations, ExecutionReference executionReference) {
        if (!featureService?.featurePresent('executionLifecycle-plugin', false)) {
            return null
        }
        if (!configurations) {
            return null
        }
        def plugins = createConfiguredPlugins(configurations, executionReference.project)
        new ExecutionReferenceLifecyclePluginHandler(
                executionReference: executionReference,
                executionLifecyclePluginService: this,
                plugins: plugins
        )
    }
}

class NamedExecutionLifecyclePlugin implements ExecutionLifecyclePlugin {
    @Delegate ExecutionLifecyclePlugin plugin
    String name
}

class JobEventStatusImpl implements JobEventStatus {
    boolean successful
    Map optionsValues
    boolean useNewValues

    @Override
    boolean useNewValues() {
        useNewValues
    }
    StepExecutionContext executionContext
    SortedSet<JobOption> options
}
