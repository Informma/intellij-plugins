// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.jetbrains.lang.dart.sdk;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.execution.wsl.WSLCommandLineOptions;
import com.intellij.execution.wsl.WSLDistribution;
import com.intellij.execution.wsl.WslPath;
import com.intellij.openapi.progress.impl.CoreProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.util.ArrayUtil;
import com.intellij.util.Consumer;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.io.URLUtil;
import com.jetbrains.lang.dart.analyzer.DartFileInfoKt;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static com.jetbrains.lang.dart.analyzer.DartAnalysisServerService.isDartSdkVersionSufficientForFileUri;

public final class DartSdk {
  public static final String DART_SDK_LIB_NAME = "Dart SDK";
  private static final String UNKNOWN_VERSION = "unknown";

  private final @NotNull String myHomePath;
  private final @NotNull String myVersion;
  private final WSLDistribution distribution;

  private DartSdk(@NotNull final String homePath, @NotNull final String version) {
    myHomePath = homePath;
    myVersion = version;
    distribution = getDistribution(homePath);
  }

  WSLDistribution getDistribution(String path) {
    WslPath wslPath = WslPath.parseWindowsUncPath(path);
    if(wslPath != null){
      return wslPath.getDistribution();
    }
    return null;
  }

  @NotNull
  public String getHomePath() {
    return myHomePath;
  }

  /**
   * @return presentable version with revision, like "1.9.1_r44672" or "1.9.0-dev.10.9_r44532" or "1.10.0-edge.44829"
   */
  @NotNull
  public String getVersion() {
    return myVersion;
  }

  @Nullable
  public static DartSdk getDartSdk(@NotNull final Project project) {
    return CachedValuesManager.getManager(project).getCachedValue(project, () -> {
      final DartSdk sdk = findDartSdkAmongLibraries(LibraryTablesRegistrar.getInstance().getLibraryTable(project).getLibraries());
      if (sdk == null) {
        return new CachedValueProvider.Result<>(null, ProjectRootManager.getInstance(project));
      }

      List<Object> dependencies = new ArrayList<>(3);
      dependencies.add(ProjectRootManager.getInstance(project));
      ContainerUtil.addIfNotNull(dependencies, LocalFileSystem.getInstance().findFileByPath(sdk.getHomePath() + "/version"));
      ContainerUtil.addIfNotNull(dependencies, LocalFileSystem.getInstance().findFileByPath(sdk.getHomePath() + "/lib/core/core.dart"));

      return new CachedValueProvider.Result<>(sdk, ArrayUtil.toObjectArray(dependencies));
    });
  }

  @Nullable
  private static DartSdk findDartSdkAmongLibraries(final Library[] libs) {
    for (final Library library : libs) {
      if (DART_SDK_LIB_NAME.equals(library.getName())) {
        return getSdkByLibrary(library);
      }
    }

    return null;
  }

  @Nullable
  public static DartSdk getSdkByLibrary(@NotNull final Library library) {
    final VirtualFile[] roots = library.getFiles(OrderRootType.CLASSES);
    final VirtualFile dartCoreRoot = DartSdkLibraryPresentationProvider.findDartCoreRoot(Arrays.asList(roots));
    if (dartCoreRoot != null) {
      final String homePath = dartCoreRoot.getParent().getParent().getPath();
      final String version = StringUtil.notNullize(DartSdkUtil.getSdkVersion(homePath), UNKNOWN_VERSION);
      return new DartSdk(homePath, version);
    }

    return null;
  }

  public boolean isWsl() {
    return distribution != null;
  }

  public @NotNull String getDartExePath() {
    if(isWsl()){
      String path = distribution.getWslPath(Path.of(myHomePath + "/bin/dart"));
      if (path != null) {
        return path;
      }
    }
    return myHomePath + (SystemInfo.isWindows ? "/bin/dart.exe" : "/bin/dart");
  }

  public @NotNull String getFullDartExePath() {
    if(isWsl()){
      return myHomePath + "/bin/dart";
    }
    return myHomePath + (SystemInfo.isWindows ? "/bin/dart.exe" : "/bin/dart");
  }

  public @NotNull String getPubPath() {
    if(isWsl()){
      String path = distribution.getWslPath(Path.of(myHomePath + "/bin/pub"));
      if (path != null) {
        return path;
      }
      return myHomePath + "/bin/pub";
    }
    return myHomePath + (SystemInfo.isWindows ? "/bin/pub.bat" : "/bin/pub");
  }

  public @NotNull String getFullPubPath() {
    if(isWsl()){
      return myHomePath + "/bin/pub";
    }
    return myHomePath + (SystemInfo.isWindows ? "/bin/pub.bat" : "/bin/pub");
  }

  public String getIDEFilePath(String uri) {
    if(isWsl()){
      return distribution.getWindowsPath(uri);
    }
    return uri;
  }

  /**
   * Returns a string, which the
   * <a href="https://htmlpreview.github.io/?https://github.com/dart-lang/sdk/blob/main/pkg/analysis_server/doc/api.html#type_FilePath">Analysis Server API specification</a>
   * defines as `FilePath`:
   * <ul>
   * <li>for SDK version 3.3 and older, it's an absolute file path with OS-dependent slashes
   * <li>for SDK version 3.4 and newer, it's a URI, thanks to the `supportsUris` capability defined in the spec
   * </ul>
   */
  public String getFileUri(@NotNull VirtualFile file) {
    String localFilePath = file.getPath();
    if(isWsl()){
      if(!WslPath.isWslUncPath(localFilePath)){
        return localFilePath;
      }
      localFilePath = distribution.getWslPath(Path.of(localFilePath));
    }
    if (!isDartSdkVersionSufficientForFileUri(myVersion)) {
      // prior to Dart SDK 3.4, the protocol required file paths instead of URIs
      if(isWsl()){
        return localFilePath;
      }
      return FileUtil.toSystemDependentName(localFilePath);
    }

    String fileUri = file.getUserData(DartFileInfoKt.DART_NOT_LOCAL_FILE_URI_KEY);
    return fileUri != null ? fileUri : getLocalFileUri(file.getPath());
  }

  /**
   * Prefer {@link #getFileUri(VirtualFile)}.
   * Use this method only if the corresponding `VirtualFile` is not available at the call site,
   * and you are sure that this is a local file path.
   *
   * Examples of paths that are processed include
   *  WslUncPath: "\\wsl.localhost\Ubuntu\ usr\lib\dart\bin\snapshots\analysis_server.dart.snapshot"
   *
   * @apiNote URI calculation is similar to {@link com.intellij.platform.lsp.api.LspServerDescriptor#getFileUri(VirtualFile)}
   * @see #getFileUri(VirtualFile)
   */
  public String getLocalFileUri(@NotNull String localFilePath) {
    localFilePath = getLocalFilePath(localFilePath);
    if (!isDartSdkVersionSufficientForFileUri(myVersion)) {
      // prior to Dart SDK 3.4, the protocol required file paths instead of URIs
      return localFilePath;
    }

    String escapedPath = URLUtil.encodePath(FileUtil.toSystemIndependentName(localFilePath));
    String url = VirtualFileManager.constructUrl(URLUtil.FILE_PROTOCOL, escapedPath);
    URI uri = VfsUtil.toUri(url);
    return uri != null ? uri.toString() : url;
  }

  public String getLocalFilePath(@NotNull String localFilePath){
    if(isWsl()){
      if(!WslPath.isWslUncPath(localFilePath)){
        return localFilePath;
      }
      localFilePath = distribution.getWslPath(Path.of(localFilePath));
      return localFilePath;
    }
    return FileUtil.toSystemDependentName(localFilePath);
  }

  public VirtualFile getLocalFile(@NotNull String localFilePath) {
    String localUri = getLocalFileUri(localFilePath);
    return VirtualFileManager.getInstance().findFileByUrl(localUri);
  }

  public List<String> getDartCommandList() {
    if (isWsl()) {
      GeneralCommandLine commandLine = new GeneralCommandLine(getDartExePath());
      try {
        distribution.patchCommandLine(commandLine, null, new WSLCommandLineOptions());
      }
      catch (ExecutionException e) {
        throw new RuntimeException(e);
      }
      return commandLine.getCommandLineList(null);
    }
    return List.of(getDartExePath());
  }

  public void patchCommandLineIfRequired(GeneralCommandLine commandLine) {
    if(isWsl()){
      try {
        distribution.patchCommandLine(commandLine, null, new WSLCommandLineOptions()
          .setExecuteCommandInShell(false));
      }
      catch (ExecutionException e) {
        throw new RuntimeException(e);
      }
    }
  }


  public List<String> getDartSimpleCommandList() {
    List<String> commandList = new ArrayList<>();
    if (isWsl()) {
      commandList.add(Objects.requireNonNull(WSLDistribution.findWslExe()).toAbsolutePath().toString());
    }
    commandList.add(getDartExePath());
    return commandList;
  }

  public ProcessOutput runCommand(GeneralCommandLine command, int timeout, @Nullable Consumer<? super ProcessHandler> processHandlerConsumer) throws ExecutionException{
    return runCommand(command, timeout, processHandlerConsumer, true);
  }

  public ProcessOutput runCommand(GeneralCommandLine command, int timeout, @Nullable Consumer<? super ProcessHandler> processHandlerConsumer, boolean destroyOnTimeout) throws ExecutionException{
    if(isWsl()){
      WSLCommandLineOptions options = new WSLCommandLineOptions();
      if(command.getWorkDirectory() != null){
        options.setRemoteWorkingDirectory(fixLinuxPath(command.getWorkDirectory().getPath()));
      }
      ProcessOutput output = distribution.executeOnWsl(command.getCommandLineList(fixLinuxPath(command.getExePath())), options, timeout, processHandlerConsumer);
      return output;
    }else{
      final CapturingProcessHandler processHandler = new CapturingProcessHandler(command);
      if(processHandlerConsumer != null){
        processHandlerConsumer.consume(processHandler);
      }
      return processHandler.runProcess(timeout, destroyOnTimeout);
    }
  }

  private void loadPath() {
    CoreProgressManager.getInstance().runProcessWithProgressSynchronously(()->{
      distribution.getShellPath();
    }, "Get WSL Shell", false, null);
    //final AtomicReference<ProcessOutput> result = new AtomicReference<>();
  }

  public static DartSdk forStageHand(String root) {
    return new DartSdk(root, Objects.requireNonNull(DartSdkUtil.getSdkVersion(root)));
  }

  private static String fixLinuxPath(String path) {
    return path.replace('\\', '/');
  }
}

