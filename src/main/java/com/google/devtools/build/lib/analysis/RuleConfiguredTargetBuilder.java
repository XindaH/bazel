// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.analysis;

import static com.google.devtools.build.lib.analysis.ExtraActionUtils.createExtraActionProvider;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.Actions;
import com.google.devtools.build.lib.actions.Actions.GeneratingActions;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.MutableActionGraph.ActionConflictException;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget;
import com.google.devtools.build.lib.analysis.constraints.ConstraintSemantics;
import com.google.devtools.build.lib.analysis.constraints.EnvironmentCollection;
import com.google.devtools.build.lib.analysis.constraints.SupportedEnvironments;
import com.google.devtools.build.lib.analysis.constraints.SupportedEnvironmentsProvider;
import com.google.devtools.build.lib.analysis.constraints.SupportedEnvironmentsProvider.RemovedEnvironmentCulprit;
import com.google.devtools.build.lib.analysis.test.ExecutionInfo;
import com.google.devtools.build.lib.analysis.test.InstrumentedFilesProvider;
import com.google.devtools.build.lib.analysis.test.TestActionBuilder;
import com.google.devtools.build.lib.analysis.test.TestEnvironmentInfo;
import com.google.devtools.build.lib.analysis.test.TestProvider;
import com.google.devtools.build.lib.analysis.test.TestProvider.TestParams;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.packages.InfoInterface;
import com.google.devtools.build.lib.packages.NativeProvider;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.Type;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import javax.annotation.Nullable;

/**
 * Builder class for analyzed rule instances.
 *
 * <p>This is used to tell Bazel which {@link TransitiveInfoProvider}s are produced by the analysis
 * of a configured target. For more information about analysis, see
 * {@link com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory}.
 *
 * @see com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory
 */
public final class RuleConfiguredTargetBuilder {
  private final RuleContext ruleContext;
  private final TransitiveInfoProviderMapBuilder providersBuilder =
      new TransitiveInfoProviderMapBuilder();
  private final Map<String, NestedSetBuilder<Artifact>> outputGroupBuilders = new TreeMap<>();

  /** These are supported by all configured targets and need to be specially handled. */
  private NestedSet<Artifact> filesToBuild = NestedSetBuilder.emptySet(Order.STABLE_ORDER);

  private NestedSetBuilder<Artifact> filesToRunBuilder = NestedSetBuilder.stableOrder();
  private RunfilesSupport runfilesSupport;
  private Artifact executable;
  private ImmutableSet<ActionAnalysisMetadata> actionsWithoutExtraAction = ImmutableSet.of();

  public RuleConfiguredTargetBuilder(RuleContext ruleContext) {
    this.ruleContext = ruleContext;
    add(LicensesProvider.class, LicensesProviderImpl.of(ruleContext));
    add(VisibilityProvider.class, new VisibilityProviderImpl(ruleContext.getVisibility()));
  }

  /**
   * Constructs the RuleConfiguredTarget instance based on the values set for this Builder.
   * Returns null if there were rule errors reported.
   */
  @Nullable
  public ConfiguredTarget build() throws ActionConflictException {
    if (ruleContext.getConfiguration().enforceConstraints()) {
      checkConstraints();
    }
    if (ruleContext.hasErrors()) {
      return null;
    }

    NestedSetBuilder<Artifact> runfilesMiddlemenBuilder = NestedSetBuilder.stableOrder();
    if (runfilesSupport != null) {
      runfilesMiddlemenBuilder.add(runfilesSupport.getRunfilesMiddleman());
      runfilesMiddlemenBuilder.addTransitive(runfilesSupport.getRunfiles().getExtraMiddlemen());
    }
    NestedSet<Artifact> runfilesMiddlemen = runfilesMiddlemenBuilder.build();
    FilesToRunProvider filesToRunProvider =
        new FilesToRunProvider(
            buildFilesToRun(runfilesMiddlemen, filesToBuild), runfilesSupport, executable);
    addProvider(new FileProvider(filesToBuild));
    addProvider(filesToRunProvider);

    if (runfilesSupport != null) {
      // If a binary is built, build its runfiles, too
      addOutputGroup(OutputGroupInfo.HIDDEN_TOP_LEVEL, runfilesMiddlemen);
    } else if (providersBuilder.contains(RunfilesProvider.class)) {
      // If we don't have a RunfilesSupport (probably because this is not a binary rule), we still
      // want to build the files this rule contributes to runfiles of dependent rules so that we
      // report an error if one of these is broken.
      //
      // Note that this is a best-effort thing: there is .getDataRunfiles() and all the language-
      // specific *RunfilesProvider classes, which we don't add here for reasons that are lost in
      // the mists of time.
      addOutputGroup(
          OutputGroupInfo.HIDDEN_TOP_LEVEL,
          providersBuilder
              .getProvider(RunfilesProvider.class)
              .getDefaultRunfiles()
              .getAllArtifacts());
    }

    // Create test action and artifacts if target was successfully initialized
    // and is a test.
    if (TargetUtils.isTestRule(ruleContext.getTarget())) {
      Preconditions.checkState(runfilesSupport != null);
      add(TestProvider.class, initializeTestProvider(filesToRunProvider));
    }

    ExtraActionArtifactsProvider extraActionsProvider =
        createExtraActionProvider(actionsWithoutExtraAction, ruleContext);
    add(ExtraActionArtifactsProvider.class, extraActionsProvider);

    if (!outputGroupBuilders.isEmpty()) {
      ImmutableMap.Builder<String, NestedSet<Artifact>> outputGroups = ImmutableMap.builder();
      for (Map.Entry<String, NestedSetBuilder<Artifact>> entry : outputGroupBuilders.entrySet()) {
        outputGroups.put(entry.getKey(), entry.getValue().build());
      }

      OutputGroupInfo outputGroupInfo = new OutputGroupInfo(outputGroups.build());
      addNativeDeclaredProvider(outputGroupInfo);
    }

    TransitiveInfoProviderMap providers = providersBuilder.build();
    AnalysisEnvironment analysisEnvironment = ruleContext.getAnalysisEnvironment();
    GeneratingActions generatingActions =
        Actions.filterSharedActionsAndThrowActionConflict(
            analysisEnvironment.getActionKeyContext(), analysisEnvironment.getRegisteredActions());
    return new RuleConfiguredTarget(
        ruleContext,
        providers,
        generatingActions.getActions(),
        generatingActions.getGeneratingActionIndex());
  }

  /**
   * Compute the artifacts to put into the {@link FilesToRunProvider} for this target. These are the
   * filesToBuild, any artifacts added by the rule with {@link #addFilesToRun}, and the runfiles'
   * middlemen if they exists.
   */
  private NestedSet<Artifact> buildFilesToRun(
      NestedSet<Artifact> runfilesMiddlemen, NestedSet<Artifact> filesToBuild) {
    filesToRunBuilder.addTransitive(filesToBuild);
    filesToRunBuilder.addTransitive(runfilesMiddlemen);
    return filesToRunBuilder.build();
  }

  /**
   * Invokes Blaze's constraint enforcement system: checks that this rule's dependencies
   * support its environments and reports appropriate errors if violations are found. Also
   * publishes this rule's supported environments for the rules that depend on it.
   */
  private void checkConstraints() {
    if (!ruleContext.getRule().getRuleClassObject().supportsConstraintChecking()) {
      return;
    }
    ConstraintSemantics constraintSemantics = ruleContext.getConstraintSemantics();
    EnvironmentCollection supportedEnvironments =
        constraintSemantics.getSupportedEnvironments(ruleContext);
    if (supportedEnvironments != null) {
      EnvironmentCollection.Builder refinedEnvironments = new EnvironmentCollection.Builder();
      Map<Label, RemovedEnvironmentCulprit> removedEnvironmentCulprits = new LinkedHashMap<>();
      constraintSemantics.checkConstraints(ruleContext, supportedEnvironments, refinedEnvironments,
          removedEnvironmentCulprits);
      add(SupportedEnvironmentsProvider.class,
          new SupportedEnvironments(supportedEnvironments, refinedEnvironments.build(),
              removedEnvironmentCulprits));
    }
  }

  private TestProvider initializeTestProvider(FilesToRunProvider filesToRunProvider) {
    int explicitShardCount = ruleContext.attributes().get("shard_count", Type.INTEGER);
    if (explicitShardCount < 0
        && ruleContext.getRule().isAttributeValueExplicitlySpecified("shard_count")) {
      ruleContext.attributeError("shard_count", "Must not be negative.");
    }
    if (explicitShardCount > 50) {
      ruleContext.attributeError("shard_count",
          "Having more than 50 shards is indicative of poor test organization. "
          + "Please reduce the number of shards.");
    }
    TestActionBuilder testActionBuilder =
        new TestActionBuilder(ruleContext)
            .setInstrumentedFiles(providersBuilder.getProvider(InstrumentedFilesProvider.class));

    TestEnvironmentInfo environmentProvider =
        (TestEnvironmentInfo)
            providersBuilder.getProvider(TestEnvironmentInfo.PROVIDER.getKey());
    if (environmentProvider != null) {
      testActionBuilder.addExtraEnv(environmentProvider.getEnvironment());
    }

    TestParams testParams =
        testActionBuilder
            .setFilesToRunProvider(filesToRunProvider)
            .setExecutionRequirements(
                (ExecutionInfo) providersBuilder
                    .getProvider(ExecutionInfo.PROVIDER.getKey()))
            .setShardCount(explicitShardCount)
            .build();
    ImmutableList<String> testTags = ImmutableList.copyOf(ruleContext.getRule().getRuleTags());
    return new TestProvider(testParams, testTags);
  }

  /**
   * Add files required to run the target. Artifacts from {@link #setFilesToBuild} and the runfiles
   * middleman, if any, are added automatically.
   */
  public RuleConfiguredTargetBuilder addFilesToRun(NestedSet<Artifact> files) {
    filesToRunBuilder.addTransitive(files);
    return this;
  }

  /** Add a specific provider. */
  public <T extends TransitiveInfoProvider> RuleConfiguredTargetBuilder addProvider(
      TransitiveInfoProvider provider) {
    providersBuilder.add(provider);
    return this;
  }

  /** Add a collection of specific providers. */
  public <T extends TransitiveInfoProvider> RuleConfiguredTargetBuilder addProviders(
      Iterable<TransitiveInfoProvider> providers) {
    providersBuilder.addAll(providers);
    return this;
  }

  /** Add a collection of specific providers. */
  public <T extends TransitiveInfoProvider> RuleConfiguredTargetBuilder addProviders(
      TransitiveInfoProviderMap providers) {
    providersBuilder.addAll(providers);
    return this;
  }


  /**
   * Add a specific provider with a given value.
   *
   * @deprecated use {@link #addProvider}
   */
  @Deprecated
  public <T extends TransitiveInfoProvider> RuleConfiguredTargetBuilder add(Class<T> key, T value) {
    return addProvider(key, value);
  }

  /** Add a specific provider with a given value. */
  public <T extends TransitiveInfoProvider> RuleConfiguredTargetBuilder addProvider(
      Class<? extends T> key, T value) {
    Preconditions.checkNotNull(key);
    Preconditions.checkNotNull(value);
    providersBuilder.put(key, value);
    return this;
  }

  private <T extends TransitiveInfoProvider> void maybeAddSkylarkLegacyProvider(
      InfoInterface value) {
    if (value.getProvider() instanceof NativeProvider.WithLegacySkylarkName) {
      addSkylarkTransitiveInfo(
          ((NativeProvider.WithLegacySkylarkName) value.getProvider()).getSkylarkName(), value);
    }
  }

  /**
   * Add a Skylark transitive info. The provider value must be safe (i.e. a String, a Boolean,
   * an Integer, an Artifact, a Label, None, a Java TransitiveInfoProvider or something composed
   * from these in Skylark using lists, sets, structs or dicts). Otherwise an EvalException is
   * thrown.
   */
  public RuleConfiguredTargetBuilder addSkylarkTransitiveInfo(
      String name, Object value, Location loc) throws EvalException {
    providersBuilder.put(name, value);
    return this;
  }

  /**
   * Adds a "declared provider" defined in Skylark to the rule. Use this method for declared
   * providers defined in Skyark.
   *
   * <p>Has special handling for {@link OutputGroupInfo}: that provider is not added from
   * Skylark directly, instead its outpuyt groups are added.
   *
   * <p>Use {@link #addNativeDeclaredProvider(InfoInterface)} in definitions of native rules.
   */
  public RuleConfiguredTargetBuilder addSkylarkDeclaredProvider(InfoInterface provider)
      throws EvalException {
    Provider constructor = provider.getProvider();
    if (!constructor.isExported()) {
      throw new EvalException(constructor.getLocation(),
          "All providers must be top level values");
    }
    if (OutputGroupInfo.SKYLARK_CONSTRUCTOR.getKey().equals(constructor.getKey())) {
      OutputGroupInfo outputGroupInfo = (OutputGroupInfo) provider;
      for (String outputGroup : outputGroupInfo) {
        addOutputGroup(outputGroup, outputGroupInfo.getOutputGroup(outputGroup));
      }
    } else {
      providersBuilder.put(provider);
    }
    return this;
  }

  /**
   * Adds "declared providers" defined in native code to the rule. Use this method for declared
   * providers in definitions of native rules.
   *
   * <p>Use {@link #addSkylarkDeclaredProvider(InfoInterface)} for Skylark rule implementations.
   */
  public RuleConfiguredTargetBuilder addNativeDeclaredProviders(Iterable<InfoInterface> providers) {
    for (InfoInterface provider : providers) {
      addNativeDeclaredProvider(provider);
    }
    return this;
  }

  /**
   * Adds a "declared provider" defined in native code to the rule. Use this method for declared
   * providers in definitions of native rules.
   *
   * <p>Use {@link #addSkylarkDeclaredProvider(InfoInterface)} for Skylark rule implementations.
   */
  public RuleConfiguredTargetBuilder addNativeDeclaredProvider(InfoInterface provider) {
    Provider constructor = provider.getProvider();
    Preconditions.checkState(constructor.isExported());
    providersBuilder.put(provider);
    maybeAddSkylarkLegacyProvider(provider);
    return this;
  }

  /**
   * Add a Skylark transitive info. The provider value must be safe.
   */
  public RuleConfiguredTargetBuilder addSkylarkTransitiveInfo(
      String name, Object value) {
    providersBuilder.put(name, value);
    return this;
  }

  /**
   * Set the runfiles support for executable targets.
   */
  public RuleConfiguredTargetBuilder setRunfilesSupport(
      RunfilesSupport runfilesSupport, Artifact executable) {
    this.runfilesSupport = runfilesSupport;
    this.executable = executable;
    return this;
  }

  /**
   * Set the files to build.
   */
  public RuleConfiguredTargetBuilder setFilesToBuild(NestedSet<Artifact> filesToBuild) {
    this.filesToBuild = filesToBuild;
    return this;
  }

  private NestedSetBuilder<Artifact> getOutputGroupBuilder(String name) {
    NestedSetBuilder<Artifact> result = outputGroupBuilders.get(name);
    if (result != null) {
      return result;
    }

    result = NestedSetBuilder.stableOrder();
    outputGroupBuilders.put(name, result);
    return result;
  }

  /**
   * Adds a set of files to an output group.
   */
  public RuleConfiguredTargetBuilder addOutputGroup(String name, NestedSet<Artifact> artifacts) {
    getOutputGroupBuilder(name).addTransitive(artifacts);
    return this;
  }

  /**
   * Adds a file to an output group.
   */
  public RuleConfiguredTargetBuilder addOutputGroup(String name, Artifact artifact) {
    getOutputGroupBuilder(name).add(artifact);
    return this;
  }

  /**
   * Adds multiple output groups.
   */
  public RuleConfiguredTargetBuilder addOutputGroups(Map<String, NestedSet<Artifact>> groups) {
    for (Map.Entry<String, NestedSet<Artifact>> group : groups.entrySet()) {
      getOutputGroupBuilder(group.getKey()).addTransitive(group.getValue());
    }

    return this;
  }

  /**
   * Set the extra action pseudo actions.
   */
  public RuleConfiguredTargetBuilder setActionsWithoutExtraAction(
      ImmutableSet<ActionAnalysisMetadata> actions) {
    this.actionsWithoutExtraAction = actions;
    return this;
  }
}
