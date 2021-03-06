package interning;

import interning.qual.Interned;
import interning.qual.PolyInterned;
import interning.qual.UnknownInterned;
import nninf.NninfAnnotatedTypeFactory;
import nninf.NninfTransfer;
import nninf.NninfVisitor;
import nninf.quals.NonNull;
import nninf.quals.Nullable;

import org.checkerframework.common.basetype.BaseAnnotatedTypeFactory;
import org.checkerframework.framework.flow.CFTransfer;
import org.checkerframework.framework.qual.PolyAll;
import org.checkerframework.framework.qual.TypeQualifiers;
import org.checkerframework.framework.source.SupportedLintOptions;
import org.checkerframework.javacutil.AnnotationUtils;

import checkers.inference.BaseInferrableChecker;
import checkers.inference.InferenceChecker;
import checkers.inference.dataflow.InferenceAnalysis;

import javax.annotation.processing.SupportedOptions;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.util.Elements;

/**
 * A type-checker plug-in for the {@link Interned} qualifier that
 * finds (and verifies the absence of) equality-testing and interning errors.
 *
 * <p>
 *
 * The {@link Interned} annotation indicates that a variable
 * refers to the canonical instance of an object, meaning that it is safe to
 * compare that object using the "==" operator. This plugin warns whenever
 * "==" is used in cases where one or both operands are not
 * {@link Interned}.  Optionally, it suggests using "=="
 * instead of ".equals" where possible.
 *
 * @checker_framework.manual #interning-checker Interning Checker
 */
@TypeQualifiers({ Interned.class, UnknownInterned.class,
    PolyInterned.class, PolyAll.class })
@SupportedLintOptions({"dotequals"})
@SupportedOptions({"checkclass"})
public final class InterningChecker extends BaseInferrableChecker {
    public AnnotationMirror INTERNED;

    @Override
    public void initChecker() {
        final Elements elements = processingEnv.getElementUtils();
        INTERNED = AnnotationUtils.fromClass(elements, Interned.class);

        super.initChecker();
    }
    
    @Override
    public InterningVisitor createVisitor(InferenceChecker ichecker, BaseAnnotatedTypeFactory factory, boolean infer)  {
        return new InterningVisitor(this, ichecker, factory, infer);
    }

    @Override
    public InterningAnnotatedTypeFactory createRealTypeFactory() {
        return new InterningAnnotatedTypeFactory(this);
    }

}
