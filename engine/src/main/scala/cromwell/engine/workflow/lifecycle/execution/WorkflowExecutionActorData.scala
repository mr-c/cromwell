package cromwell.engine.workflow.lifecycle.execution

import akka.actor.ActorRef
import cromwell.backend._
import cromwell.core.ExecutionStatus._
import cromwell.core.{JobKey, _}
import cromwell.engine.workflow.lifecycle.execution.WorkflowExecutionActorData.DataStoreUpdate
import cromwell.engine.workflow.lifecycle.execution.keys._
import cromwell.engine.workflow.lifecycle.execution.stores.ValueStore.ValueKey
import cromwell.engine.workflow.lifecycle.execution.stores.{ExecutionStore, ValueStore}
import cromwell.engine.{EngineWorkflowDescriptor, WdlFunctions}
import wom.values.WomValue

object WorkflowExecutionDiff {
  def empty = WorkflowExecutionDiff(Map.empty)
}
/** Data differential between current execution data, and updates performed in a method that needs to be merged. */
final case class WorkflowExecutionDiff(executionStoreChanges: Map[JobKey, ExecutionStatus],
                                       jobKeyActorMappings: Map[ActorRef, JobKey] = Map.empty,
                                       valueStoreAdditions: Map[ValueKey, WomValue] = Map.empty) {
  def containsNewEntry: Boolean = {
    executionStoreChanges.exists(esc => esc._2 == NotStarted) || valueStoreAdditions.nonEmpty
  }
}

object WorkflowExecutionActorData {
  def apply(workflowDescriptor: EngineWorkflowDescriptor): WorkflowExecutionActorData = {
    WorkflowExecutionActorData(
      workflowDescriptor,
      ExecutionStore(workflowDescriptor.callable),
      ValueStore.initialize(workflowDescriptor.knownValues)
    )
  }

  final case class DataStoreUpdate(runnableKeys: List[JobKey], newData: WorkflowExecutionActorData)
}

case class WorkflowExecutionActorData(workflowDescriptor: EngineWorkflowDescriptor,
                                      executionStore: ExecutionStore,
                                      valueStore: ValueStore,
                                      jobKeyActorMappings: Map[ActorRef, JobKey] = Map.empty,
                                      jobFailures: Map[JobKey, Throwable] = Map.empty,
                                      downstreamExecutionMap: JobExecutionMap = Map.empty) {

  val expressionLanguageFunctions = new WdlFunctions(workflowDescriptor.pathBuilders)

  def sealExecutionStore: WorkflowExecutionActorData = this.copy(
    executionStore = executionStore.seal
  )

  def callExecutionSuccess(jobKey: JobKey, outputs: CallOutputs): WorkflowExecutionActorData = {
    mergeExecutionDiff(WorkflowExecutionDiff(
      executionStoreChanges = Map(jobKey -> Done),
      valueStoreAdditions = toValuesMap(jobKey, outputs)
    ))
  }

  final def expressionEvaluationSuccess(expressionKey: ExpressionKey, value: WomValue): WorkflowExecutionActorData = {
    val valueStoreKey = ValueKey(expressionKey.singleOutputPort, expressionKey.index)
    mergeExecutionDiff(WorkflowExecutionDiff(
      executionStoreChanges = Map(expressionKey -> Done),
      valueStoreAdditions = Map(valueStoreKey -> value)
    ))
  }

  def executionFailure(failedJobKey: JobKey, reason: Throwable, jobExecutionMap: JobExecutionMap): WorkflowExecutionActorData = {
    mergeExecutionDiff(WorkflowExecutionDiff(
      executionStoreChanges = Map(failedJobKey -> ExecutionStatus.Failed))
    ).addExecutions(jobExecutionMap)
    .copy(
      jobFailures = jobFailures + (failedJobKey -> reason)
    )
  }

  def executionFailed(jobKey: JobKey): WorkflowExecutionActorData = mergeExecutionDiff(WorkflowExecutionDiff(Map(jobKey -> ExecutionStatus.Failed)))

  /** Converts call outputs to a ValueStore entries */
  private def toValuesMap(jobKey: JobKey, outputs: CallOutputs): Map[ValueKey, WomValue] = {
    outputs.outputs.map({
      case (outputPort, jobOutput) => ValueKey(outputPort, jobKey.index) -> jobOutput
    })
  }

  def addExecutions(jobExecutionMap: JobExecutionMap): WorkflowExecutionActorData = {
    this.copy(downstreamExecutionMap = downstreamExecutionMap ++ jobExecutionMap)
  }

  def removeJobKeyActor(actorRef: ActorRef): WorkflowExecutionActorData = {
    this.copy(
      jobKeyActorMappings = jobKeyActorMappings - actorRef
    )
  }

  def mergeExecutionDiff(diff: WorkflowExecutionDiff): WorkflowExecutionActorData = {
    this.copy(
      executionStore = executionStore.updateKeys(diff.executionStoreChanges),
      valueStore = valueStore.add(diff.valueStoreAdditions),
      jobKeyActorMappings = jobKeyActorMappings ++ diff.jobKeyActorMappings
    )
  }

  def mergeExecutionDiffs(diffs: Traversable[WorkflowExecutionDiff]): WorkflowExecutionActorData = {
    diffs.foldLeft(this)((newData, diff) => newData.mergeExecutionDiff(diff))
  }

  def jobExecutionMap: JobExecutionMap = {
    downstreamExecutionMap updated (workflowDescriptor.backendDescriptor, executionStore.startedJobs)
  }
  
  def executionStoreUpdate: DataStoreUpdate = {
    val update = executionStore.update
    DataStoreUpdate(update.runnableKeys, this.copy(executionStore = update.updatedStore))
  }

  def done: Boolean = executionStore.isDone
}
