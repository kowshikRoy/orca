/*
 * Copyright 2016 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.orca.clouddriver.pipeline.servergroup.strategies

import com.netflix.spinnaker.kork.exceptions.UserException
import com.netflix.spinnaker.orca.api.pipeline.models.StageExecution
import com.netflix.spinnaker.orca.clouddriver.pipeline.servergroup.ResizeServerGroupStage
import com.netflix.spinnaker.orca.clouddriver.pipeline.servergroup.UpdateLaunchTemplateStage
import com.netflix.spinnaker.orca.clouddriver.utils.OortHelper
import com.netflix.spinnaker.orca.kato.pipeline.ModifyAsgLaunchConfigurationStage
import com.netflix.spinnaker.orca.kato.pipeline.RollingPushStage
import com.netflix.spinnaker.orca.kato.pipeline.support.SourceResolver
import com.netflix.spinnaker.orca.api.pipeline.SyntheticStageOwner
import com.netflix.spinnaker.orca.pipeline.StageExecutionFactory
import groovy.transform.Immutable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
@Deprecated
class RollingPushStrategy implements Strategy {

  final String name = "rollingpush"

  @Autowired
  ModifyAsgLaunchConfigurationStage modifyAsgLaunchConfigurationStage

  @Autowired
  UpdateLaunchTemplateStage updateLaunchTemplateStage

  @Autowired
  ResizeServerGroupStage resizeServerGroupStage

  @Autowired
  OortHelper oortHelper

  @Autowired
  RollingPushStage rollingPushStage

  @Autowired
  SourceResolver sourceResolver

  @Override
  List<StageExecution> composeBeforeStages(StageExecution parent) {
    def source = sourceResolver.getSource(parent)

    if (!source) {
      throw new UserException("Could not find source server group for rolling push. Does the specified cluster exist?")
    }

    return Collections.emptyList()
  }

  @Override
  List<StageExecution> composeAfterStages(StageExecution stage) {
    def stages = []
    def source = sourceResolver.getSource(stage)

    if (!source) {
      throw new UserException("Could not find source server group for rolling push. Does the specified cluster exist?")
    }

    def modifyCtx = stage.context + [
        region: source.region,
        regions: [source.region],
        asgName: source.asgName,
        'deploy.server.groups': [(source.region): [source.asgName]],
        useSourceCapacity: true,
        credentials: source.account,
        source: [
            asgName: source.asgName,
            account: source.account,
            region: source.region,
            useSourceCapacity: true
        ]
    ]

    stages << StageExecutionFactory.newStage(
      stage.execution,
      stage.context.setLaunchTemplate ? updateLaunchTemplateStage.type : modifyAsgLaunchConfigurationStage.type,
      stage.context.setLaunchTemplate ? "updateLaunchTemplate" : "modifyLaunchConfiguration",
      modifyCtx,
      stage,
      SyntheticStageOwner.STAGE_AFTER
    )

    def sourceServerGroup = oortHelper.getTargetServerGroup(
        source.account,
        source.asgName,
        source.region,
        stage.context.cloudProvider
    ).orElse(null)

    if(!sourceServerGroup) {
      throw new UserException("Could not find source server group for rolling push. Does the specified cluster exist?")
    }

    def terminationConfig = stage.mapTo("/termination", TerminationConfig)
    def resizeCtx = [
        serverGroupName: source.asgName,
        credentials: source.account,
        cloudprovider: stage.context.cloudProvider,
        asgName: source.asgName,
        moniker: stage.context.moniker,
        region: source.region,
        targetLocation: [
            type: 'REGION',
            value: source.region
        ],
        capacity: [
            min: sourceServerGroup.capacity.min + terminationConfig.concurrentRelaunches,
            max: sourceServerGroup.capacity.max + terminationConfig.concurrentRelaunches,
            desired: sourceServerGroup.capacity.desired + terminationConfig.concurrentRelaunches
        ]
    ]
    stages << StageExecutionFactory.newStage(
      stage.execution,
      resizeServerGroupStage.type,
      "Increase ASG capacity by ${terminationConfig.concurrentRelaunches}",
      resizeCtx,
      stage,
      SyntheticStageOwner.STAGE_AFTER
    )

    if (terminationConfig.relaunchAllInstances || terminationConfig.totalRelaunches > 0) {
      stages << StageExecutionFactory.newStage(
        stage.execution,
        rollingPushStage.type,
        "rollingPush",
        modifyCtx,
        stage,
        SyntheticStageOwner.STAGE_AFTER
      )
    }

    resizeCtx = resizeCtx + [
        capacity: [
            min: sourceServerGroup.capacity.min,
            max: sourceServerGroup.capacity.max
        ]
    ]

    stages << StageExecutionFactory.newStage(
      stage.execution,
      resizeServerGroupStage.type,
      "Restore Original ASG Capacity",
      resizeCtx,
      stage,
      SyntheticStageOwner.STAGE_AFTER
    )

    return stages
  }

  @Override
  boolean replacesBasicSteps() {
    return true
  }

  @Immutable
  static class TerminationConfig {
    String order
    boolean relaunchAllInstances
    int concurrentRelaunches
    int totalRelaunches
  }
}
