// This is a generated file. Not intended for manual editing.
package org.intellij.terraform.hil.psi;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElement;

public interface ILTemplateIfBlockExpression extends ILExpression {

  @Nullable
  ElseBranch getElseBranch();

  @Nullable
  EndIfBranch getEndIfBranch();

  @NotNull
  IfBranch getIfBranch();

}
