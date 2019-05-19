/*
 * Copyright 2011-present Greg Shrago
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.intellij.grammar.actions;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.ProjectScope;
import com.intellij.util.ObjectUtils;
import org.intellij.grammar.BnfFileType;
import org.intellij.grammar.config.Options;
import org.intellij.grammar.generator.BnfConstants;
import org.intellij.jflex.parser.JFlexFileType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static com.intellij.util.ArrayUtil.getFirstElement;

/**
 * @author gregsh
 */
public class FileGeneratorUtil {
  @NotNull
  public static VirtualFile getTargetDirectoryFor(@NotNull Project project,
                                                  @NotNull VirtualFile sourceFile,
                                                  @Nullable String targetFile,
                                                  @Nullable String targetPackage,
                                                  boolean returnRoot) {
    boolean hasPackage = StringUtil.isNotEmpty(targetPackage);
    ProjectRootManager rootManager = ProjectRootManager.getInstance(project);
    ProjectFileIndex fileIndex = ProjectFileIndex.SERVICE.getInstance(project);

    VirtualFile existingFile = null;
    if (targetFile != null) {
      Collection<VirtualFile> fromIndex = FilenameIndex.getVirtualFilesByName(project, targetFile, ProjectScope.getProjectScope(project));
      List<VirtualFile> files = new ArrayList<>(fromIndex);
      files.sort((f1, f2) -> {
        boolean b1 = fileIndex.isInSource(f1);
        boolean b2 = fileIndex.isInSource(f2);
        if (b1 != b2) return b1 ? -1 : 1;
        return Comparing.compare(f1.getPath().length(), f2.getPath().length());
      });
      for (VirtualFile file : files) {
        String existingFilePackage = fileIndex.getPackageNameByDirectory(file.getParent());
        if (!hasPackage || existingFilePackage == null || targetPackage.equals(existingFilePackage)) {
          existingFile = file;
          break;
        }
      }
    }

    VirtualFile existingFileRoot =
      existingFile == null ? null :
      fileIndex.isInSourceContent(existingFile) ? fileIndex.getSourceRootForFile(existingFile) :
      fileIndex.isInContent(existingFile) ? fileIndex.getContentRootForFile(existingFile) : null;

    boolean preferGenRoot = sourceFile.getFileType() == BnfFileType.INSTANCE ||
                            sourceFile.getFileType() == JFlexFileType.INSTANCE;
    boolean preferSourceRoot = hasPackage && !preferGenRoot;
    VirtualFile[] sourceRoots = rootManager.getContentSourceRoots();
    VirtualFile[] contentRoots = rootManager.getContentRoots();
    final VirtualFile virtualRoot = existingFileRoot != null ? existingFileRoot :
                                    preferSourceRoot && fileIndex.isInSource(sourceFile) ? fileIndex.getSourceRootForFile(sourceFile) :
                                    fileIndex.isInContent(sourceFile) ? fileIndex.getContentRootForFile(sourceFile) :
                                    getFirstElement(preferSourceRoot && sourceRoots.length > 0? sourceRoots : contentRoots);
    if (virtualRoot == null) {
      fail(project, sourceFile, "Unable to guess target source root");
      throw new ProcessCanceledException();
    }
    try {
      String genDirName = Options.GEN_DIR.get();
      boolean newGenRoot = !fileIndex.isInSourceContent(virtualRoot);
      final String relativePath = (hasPackage && newGenRoot ? genDirName + "/" + targetPackage :
                                  hasPackage ? targetPackage :
                                  newGenRoot ? genDirName : "").replace('.', '/');
      if (relativePath.isEmpty()) {
        return virtualRoot;
      }
      else {
        VirtualFile result = WriteAction.compute(() -> VfsUtil.createDirectoryIfMissing(virtualRoot, relativePath));
        VfsUtil.markDirtyAndRefresh(false, true, true, result);
        return returnRoot && newGenRoot ? ObjectUtils.assertNotNull(virtualRoot.findChild(genDirName)) :
               returnRoot ? virtualRoot : result;
      }
    }
    catch (ProcessCanceledException ex) {
      throw ex;
    }
    catch (Exception ex) {
      fail(project, sourceFile, ex.getMessage());
      throw new ProcessCanceledException();
    }
  }

  static void fail(@NotNull Project project, @NotNull VirtualFile sourceFile, @NotNull String message) {
    fail(project, sourceFile.getName(), message);
  }

  static void fail(@NotNull Project project, @NotNull String title, @NotNull String message) {
    Notifications.Bus.notify(new Notification(
      BnfConstants.GENERATION_GROUP,
      title, message,
      NotificationType.ERROR), project);
    throw new ProcessCanceledException();
  }
}
