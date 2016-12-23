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
package com.jetbrains.python.codeInsight;

import com.intellij.lang.ASTNode;
import com.intellij.lang.injection.MultiHostRegistrar;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.util.containers.ContainerUtil;
import com.jetbrains.python.PyNames;
import com.jetbrains.python.codeInsight.fstrings.FStringParser;
import com.jetbrains.python.psi.*;
import com.jetbrains.python.psi.PyUtil.StringNodeInfo;
import com.jetbrains.python.psi.impl.PyCallExpressionNavigator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

import static com.jetbrains.python.inspections.PyStringFormatParser.*;

/**
 * @author vlan
 */
public class PyInjectionUtil {

  private static final String PLACEHOLDER = "missing_value";

  public static class InjectionResult {
    public static InjectionResult EMPTY = new InjectionResult(false, true);

    private final boolean myInjected;
    private final boolean myStrict;

    public InjectionResult(boolean injected, boolean strict) {
      myInjected = injected;
      myStrict = strict;
    }

    public boolean isInjected() {
      return myInjected;
    }

    public boolean isStrict() {
      return myStrict;
    }

    public InjectionResult append(@NotNull InjectionResult result) {
      return new InjectionResult(myInjected || result.isInjected(), myStrict && result.isStrict());
    }
  }

  public static final List<Class<? extends PsiElement>> ELEMENTS_TO_INJECT_IN =
    Arrays.asList(PyStringLiteralExpression.class, PyParenthesizedExpression.class, PyBinaryExpression.class, PyCallExpression.class,
                  PsiComment.class);

  private PyInjectionUtil() {}

  /**
   * Returns the largest expression in the specified context that represents a string literal suitable for language injection, possibly
   * with concatenation, parentheses, or formatting.
   */
  @Nullable
  public static PsiElement getLargestStringLiteral(@NotNull PsiElement context) {
    PsiElement element = null;
    for (PsiElement current = context; current != null && isStringLiteralPart(current, element); current = current.getParent()) {
      element = current;
    }
    return element;
  }

  /**
   * Registers language injections in the given registrar for the specified string literal element or its ancestor that contains
   * string concatenations or formatting.
   */
  @NotNull
  public static InjectionResult registerStringLiteralInjection(@NotNull PsiElement element, @NotNull MultiHostRegistrar registrar) {
    return processStringLiteral(element, registrar, "", "", Formatting.NONE);
  }

  private static boolean isStringLiteralPart(@NotNull PsiElement element, @Nullable PsiElement context) {
    if (element == context || element instanceof PyStringLiteralExpression || element instanceof PsiComment) {
      return true;
    }
    else if (element instanceof PyParenthesizedExpression) {
      final PyExpression contained = ((PyParenthesizedExpression)element).getContainedExpression();
      return contained != null && isStringLiteralPart(contained, context);
    }
    else if (element instanceof PyBinaryExpression) {
      final PyBinaryExpression expr = (PyBinaryExpression)element;
      final PyExpression left = expr.getLeftExpression();
      final PyExpression right = expr.getRightExpression();
      if (expr.isOperator("+")) {
        return isStringLiteralPart(left, context) || right != null && isStringLiteralPart(right, context);
      }
      else if (expr.isOperator("%")) {
        return right != context && isStringLiteralPart(left, context);
      }
      return false;
    }
    else if (element instanceof PyCallExpression) {
      final PyExpression qualifier = getFormatCallQualifier((PyCallExpression)element);
      return qualifier != null && isStringLiteralPart(qualifier, context);
    }
    else if (element instanceof PyReferenceExpression) {
      final PyCallExpression callExpr = PyCallExpressionNavigator.getPyCallExpressionByCallee(element);
      return callExpr != null && isStringLiteralPart(callExpr, context);
    }
    return false;
  }

  @Nullable
  private static PyExpression getFormatCallQualifier(@NotNull PyCallExpression element) {
    final PyExpression callee = element.getCallee();
    if (callee instanceof PyQualifiedExpression) {
      final PyQualifiedExpression qualifiedExpr = (PyQualifiedExpression)callee;
      final PyExpression qualifier = qualifiedExpr.getQualifier();
      if (qualifier != null && PyNames.FORMAT.equals(qualifiedExpr.getReferencedName())) {
        return qualifier;
      }
    }
    return null;
  }

  @NotNull
  private static InjectionResult processStringLiteral(@NotNull PsiElement element, @NotNull MultiHostRegistrar registrar,
                                                      @NotNull String prefix, @NotNull String suffix, @NotNull Formatting formatting) {
    if (element instanceof PyStringLiteralExpression) {
      boolean injected = false;
      boolean strict = true;
      
      final PyStringLiteralExpression expr = (PyStringLiteralExpression)element;
      for (ASTNode node : expr.getStringNodes()) {
        final StringNodeInfo nodeInfo = new StringNodeInfo(node);
        final int nodeOffsetInLiteral = node.getTextRange().getStartOffset() - expr.getTextRange().getStartOffset();
        final int contentEnd = nodeInfo.getContentRange().getEndOffset();
        final int contentStart = nodeInfo.getContentRange().getStartOffset();
        final List<TextRange> subsChunkRanges;
        if (nodeInfo.isFormatted()) {
          // TODO unify interfaces of FStringParser and PyStringFormatParser so they could be used more or the less interchangeably
          final List<FStringParser.Fragment> fragments = FStringParser.parse(node.getText()).getFragments();
          subsChunkRanges = ContainerUtil.mapNotNull(fragments, t -> {
            if (t.getDepth() == 1) {
              final int rightOffset = t.getRightBraceOffset();
              // For FStringParser offsets already take into account prefix and opening quotes
              return TextRange.create(t.getLeftBraceOffset(), rightOffset < 0 ? contentEnd : rightOffset + 1).shiftRight(nodeOffsetInLiteral);
            }
            return null;
          });
        }
        else if (formatting == Formatting.NONE) {
          // No formatting at all: inject in the whole string literal between quotes
          registrar.addPlace(prefix, suffix, expr, nodeInfo.getContentRange().shiftRight(nodeOffsetInLiteral));
          injected = true;
          continue;
        }
        else {
          final String content = nodeInfo.getContent();
          final List<FormatStringChunk> allChunks;
          if (formatting == Formatting.NEW_STYLE) {
            allChunks = parseNewStyleFormat(content);
          }
          else {
            allChunks = parsePercentFormat(content);
          }
          subsChunkRanges = ContainerUtil.mapNotNull(allChunks, chunk -> {
            return chunk instanceof SubstitutionChunk ? chunk.getTextRange().shiftRight(nodeOffsetInLiteral + contentStart) : null;
          });
        }
        
        if (!subsChunkRanges.isEmpty()) {
          strict = false;
        }

        int literalChunkStart = nodeInfo.getContentRange().getStartOffset();
        int literalChunkEnd;
        final TextRange endSentinel = TextRange.from(contentEnd, 0);
        for (final TextRange subsChunkRange : ContainerUtil.append(subsChunkRanges, endSentinel)) {
          literalChunkEnd = subsChunkRange.getStartOffset();

          if (literalChunkStart < literalChunkEnd) {
            final String chunkPrefix;
            final TextRange beforeChunkRange = TextRange.create(contentStart, literalChunkStart);
            if (literalChunkStart == contentStart) {
              chunkPrefix = prefix;
            }
            else if (subsChunkRanges.get(0).equals(beforeChunkRange)) {
              chunkPrefix = PLACEHOLDER;
            }
            else {
              chunkPrefix = "";
            }

            final String chunkSuffix;
            if (subsChunkRange == endSentinel) {
              chunkSuffix = suffix;
            }
            else {
              chunkSuffix = PLACEHOLDER;
            }
            registrar.addPlace(chunkPrefix, chunkSuffix, expr, TextRange.create(literalChunkStart, literalChunkEnd));
            injected = true;
          }
          literalChunkStart = subsChunkRange.getEndOffset();
        }
      }
      return new InjectionResult(injected, strict);
    }
    else if (element instanceof PyParenthesizedExpression) {
      final PyExpression contained = ((PyParenthesizedExpression)element).getContainedExpression();
      if (contained != null) {
        return processStringLiteral(contained, registrar, prefix, suffix, formatting);
      }
    }
    else if (element instanceof PyBinaryExpression) {
      final PyBinaryExpression expr = (PyBinaryExpression)element;
      final PyExpression left = expr.getLeftExpression();
      final PyExpression right = expr.getRightExpression();
      final boolean isLeftString = isStringLiteralPart(left, null);
      if (expr.isOperator("+")) {
        final boolean isRightString = right != null && isStringLiteralPart(right, null);
        InjectionResult result = InjectionResult.EMPTY;
        if (isLeftString) {
          result = result.append(processStringLiteral(left, registrar, prefix, isRightString ? "" : PLACEHOLDER, formatting));
        }
        if (isRightString) {
          result = result.append(processStringLiteral(right, registrar, isLeftString ? "" : PLACEHOLDER, suffix, formatting));
        }
        return result;
      }
      else if (expr.isOperator("%")) {
        return processStringLiteral(left, registrar, prefix, suffix, Formatting.PERCENT);
      }
    }
    else if (element instanceof PyCallExpression) {
      final PyExpression qualifier = getFormatCallQualifier((PyCallExpression)element);
      if (qualifier != null) {
        return processStringLiteral(qualifier, registrar, prefix, suffix, Formatting.NEW_STYLE);
      }
    }
    return InjectionResult.EMPTY;
  }

  private enum Formatting {
    NONE,
    PERCENT,
    NEW_STYLE
  }
}
