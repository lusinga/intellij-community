/*
 * Copyright 2000-2014 JetBrains s.r.o.
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

package com.intellij.codeInsight.daemon.impl.quickfix;

import com.intellij.codeInsight.daemon.QuickFixActionRegistrar;
import com.intellij.codeInsight.daemon.QuickFixBundle;
import com.intellij.codeInsight.guess.GuessManager;
import com.intellij.codeInsight.intention.HighPriorityAction;
import com.intellij.codeInsight.intention.impl.BaseIntentionAction;
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.util.PsiTypesUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.psi.util.TypeConversionUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

import static com.intellij.util.ObjectUtils.assertNotNull;

public class AddTypeCastFix extends LocalQuickFixAndIntentionActionOnPsiElement implements HighPriorityAction {
  private final PsiType myType;
  private final String myName;

  public AddTypeCastFix(@NotNull PsiType type, @NotNull PsiExpression expression) {
    this(type, expression, "add.typecast.text");
  }

  public AddTypeCastFix(@NotNull PsiType type, @NotNull PsiExpression expression, String messageKey) {
    super(expression);
    myType = type;
    myName = QuickFixBundle.message(messageKey, type.isValid() ? type.getCanonicalText() : "");
  }

  @Override
  @NotNull
  public String getText() {
    return myName;
  }

  @Override
  @NotNull
  public String getFamilyName() {
    return QuickFixBundle.message("add.typecast.family");
  }

  @Override
  public boolean isAvailable(@NotNull Project project,
                             @NotNull PsiFile file,
                             @NotNull PsiElement startElement,
                             @NotNull PsiElement endElement) {
    return myType.isValid() &&
           !PsiType.VOID.equals(myType) &&
           PsiTypesUtil.isDenotableType(myType, startElement) &&
           PsiTypesUtil.allTypeParametersResolved(startElement, myType) &&
           BaseIntentionAction.canModify(startElement);
  }

  @Override
  public void invoke(@NotNull Project project,
                     @NotNull PsiFile file,
                     @Nullable Editor editor,
                     @NotNull PsiElement startElement,
                     @NotNull PsiElement endElement) {
    addTypeCast(project, (PsiExpression)startElement, myType);
  }

  private static void addTypeCast(Project project, PsiExpression originalExpression, PsiType type) {
    PsiExpression typeCast = createCastExpression(originalExpression, project, type);
    originalExpression.replace(typeCast);
  }

  static PsiExpression createCastExpression(PsiExpression original, Project project, PsiType type) {
    // remove nested casts
    PsiElement expression = PsiUtil.deparenthesizeExpression(original);
    if (expression == null) return null;

    if (type.equals(PsiType.NULL)) return null;
    if (type instanceof PsiEllipsisType) type = ((PsiEllipsisType)type).toArrayType();
    String text = "(" + type.getCanonicalText(false) + ")value";
    PsiElementFactory factory = JavaPsiFacade.getElementFactory(original.getProject());
    PsiTypeCastExpression typeCast = (PsiTypeCastExpression)factory.createExpressionFromText(text, original);
    typeCast = (PsiTypeCastExpression)JavaCodeStyleManager.getInstance(project).shortenClassReferences(typeCast);
    typeCast = (PsiTypeCastExpression)CodeStyleManager.getInstance(project).reformat(typeCast);

    if (expression instanceof PsiConditionalExpression) {
      // we'd better cast one branch of ternary expression if we can
      PsiConditionalExpression conditional = (PsiConditionalExpression)expression.copy();
      PsiExpression thenE = conditional.getThenExpression();
      PsiExpression elseE = conditional.getElseExpression();
      PsiType thenType = thenE == null ? null : thenE.getType();
      PsiType elseType = elseE == null ? null : elseE.getType();
      if (elseType != null && thenType != null) {
        boolean replaceThen = !TypeConversionUtil.isAssignable(type, thenType);
        boolean replaceElse = !TypeConversionUtil.isAssignable(type, elseType);
        if (replaceThen != replaceElse) {
          if (replaceThen) {
            assertNotNull(typeCast.getOperand()).replace(thenE);
            thenE.replace(typeCast);
          }
          else {
            assertNotNull(typeCast.getOperand()).replace(elseE);
            elseE.replace(typeCast);
          }
          return conditional;
        }
      }
    }

    assertNotNull(typeCast.getOperand()).replace(expression);

    return typeCast;
  }

  public static void registerFix(QuickFixActionRegistrar registrar,
                                 PsiExpression qualifier,
                                 PsiJavaCodeReferenceElement ref, 
                                 TextRange fixRange) {
    String referenceName = ref.getReferenceName();
    if (referenceName == null) return;
    PsiElement gParent = ref.getParent();
    List<PsiType> conjuncts = GuessManager.getInstance(qualifier.getProject()).getControlFlowExpressionTypeConjuncts(qualifier);
    for (PsiType conjunct : conjuncts) {
      PsiClass psiClass = PsiUtil.resolveClassInType(conjunct);
      if (psiClass == null) continue;
      if (gParent instanceof PsiMethodCallExpression) {
        if (psiClass.findMethodsByName(referenceName).length == 0) {
          continue;
        }
      }
      else if (psiClass.findFieldByName(referenceName, true) == null) {
        continue;
      }
      registrar.register(fixRange, new AddTypeCastFix(conjunct, qualifier, "add.qualifier.typecast.text"), null);
    }
  }
}
