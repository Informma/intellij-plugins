// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.jetbrains.lang.dart.projectWizard;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.NlsSafe;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.SystemProperties;
import com.jetbrains.lang.dart.ide.actions.DartPubActionBase;
import com.jetbrains.lang.dart.sdk.DartSdk;
import com.jetbrains.lang.dart.sdk.DartSdkUtil;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class Stagehand {

  private static final String DART_CREATE_MIN_SDK_VERSION = "2.10";

  static boolean isUseDartCreate(@NotNull String sdkHomePath) {
    String version = DartSdkUtil.getSdkVersion(sdkHomePath);
    return version != null && StringUtil.compareVersionNumbers(version, DART_CREATE_MIN_SDK_VERSION) >= 0;
  }

  public static class StagehandDescriptor {
    public final @NotNull @NonNls String myId;
    public final @NotNull @NlsSafe String myLabel;
    public final @NotNull @NlsSafe String myDescription;
    public final @Nullable @NonNls String myEntrypoint;

    public StagehandDescriptor(@NotNull @NonNls String id,
                               @NotNull @NlsSafe String label,
                               @NotNull @NlsSafe String description,
                               @Nullable @NonNls String entrypoint) {
      myId = id;
      myLabel = label;
      myDescription = description;
      myEntrypoint = entrypoint;
    }

    @Override
    public String toString() {
      return StringUtil.join("[", myId, ",", myLabel, ",", myDescription, ",", myEntrypoint, "]");
    }
  }

  private static final Logger LOG = Logger.getInstance(Stagehand.class);
  private static final List<StagehandDescriptor> EMPTY = new ArrayList<>();

  private static ProcessOutput runDartCreate(@NotNull DartSdk sdk,
                                             @Nullable String workingDirectory,
                                             int timeoutInSeconds,
                                             String... parameters) throws ExecutionException {

    // working directory is used by command not by dart so file uri is not valid
    //if(workingDirectory != null) {
    //  workingDirectory = sdk.getLocalFileUri(workingDirectory);
    //}
    workingDirectory = sdk.getLocalFilePath(workingDirectory);
    final GeneralCommandLine command = new GeneralCommandLine()
      .withExePath(sdk.getDartExePath())
      .withWorkDirectory(workingDirectory);

    command.addParameter("create");
    command.addParameters(parameters);

    return sdk.runCommand(command, timeoutInSeconds * 1000, null, false);
  }

  private static ProcessOutput runPubGlobal(@NotNull DartSdk sdk,
                                            @Nullable String workingDirectory,
                                            int timeoutInSeconds,
                                            @NotNull String pubEnvVarSuffix,
                                            String... pubParameters) throws ExecutionException {
    if(workingDirectory != null) {
      workingDirectory = sdk.getLocalFileUri(workingDirectory);
    }
    final GeneralCommandLine command = new GeneralCommandLine()
      .withExePath(sdk.getPubPath())
      .withWorkDirectory(workingDirectory)
      .withEnvironment(DartPubActionBase.PUB_ENV_VAR_NAME, DartPubActionBase.getPubEnvValue() + ".stagehand" + pubEnvVarSuffix);

    command.addParameter("global");
    command.addParameters(pubParameters);

    return sdk.runCommand(command, timeoutInSeconds * 1000, null, false);
    //return new CapturingProcessHandler(command).runProcess(timeoutInSeconds * 1000, false);
  }

  public void generateInto(@NotNull final DartSdk sdk,
                           @NotNull final VirtualFile projectDirectory,
                           @NotNull final String templateId) throws ExecutionException {
    ProcessOutput output = isUseDartCreate(sdk.getHomePath())
                           ? runDartCreate(sdk, projectDirectory.getParent().getPath(), 30, "--force", "--no-pub", "--template",
                                           templateId, projectDirectory.getName())
                           : runPubGlobal(sdk, projectDirectory.getPath(), 30, "", "run", "stagehand", "--author",
                                          SystemProperties.getUserName(), templateId);

    if (output.getExitCode() != 0) {
      throw new ExecutionException(output.getStderr());
    }
  }

  public List<StagehandDescriptor> getAvailableTemplates(@NotNull final String sdkRoot) {
    try {

      DartSdk tempSdk = DartSdk.forStageHand(sdkRoot);

      ProcessOutput output = isUseDartCreate(sdkRoot)
                             ? runDartCreate(tempSdk, null, 10, "--list-templates")
                             : runPubGlobal(tempSdk, null, 10, "", "run", "stagehand", "--machine");

      int exitCode = output.getExitCode();

      if (exitCode != 0) {
        return EMPTY;
      }

      // [{"name":"consoleapp", "label":"Console App", "description":"A minimal command-line application."}, {"name": ..., }]
      JSONArray arr = new JSONArray(output.getStdout());
      List<StagehandDescriptor> result = new ArrayList<>();

      for (int i = 0; i < arr.length(); i++) {
        JSONObject obj = arr.getJSONObject(i);

        result.add(new StagehandDescriptor(
          obj.getString("name"),
          obj.getString("label"),
          obj.getString("description"),
          obj.optString("entrypoint")));
      }

      if (!isUseDartCreate(sdkRoot)) {
        // Sort the stagehand templates lexically by name.
        result.sort((one, two) -> one.myLabel.compareToIgnoreCase(two.myLabel));
      }

      return result;
    }
    catch (ExecutionException | JSONException e) {
      LOG.info(e);
    }

    return EMPTY;
  }

  public void install(@NotNull final String sdkRoot) {
    if (isUseDartCreate(sdkRoot)) return;

    try {
      DartSdk tempSdk = DartSdk.forStageHand(sdkRoot);
      runPubGlobal(tempSdk, null, 60, ".activate", "activate", "stagehand");
    }
    catch (ExecutionException e) {
      LOG.info(e);
    }
  }
}
