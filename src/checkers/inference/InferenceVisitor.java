package checkers.inference;

/*>>>
import org.checkerframework.checker.compilermsgs.qual.CompilerMessageKey;
*/

import checkers.inference.util.InferenceUtil;
import org.checkerframework.common.basetype.BaseAnnotatedTypeFactory;
import org.checkerframework.common.basetype.BaseTypeVisitor;
import org.checkerframework.framework.source.Result;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedArrayType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedDeclaredType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedExecutableType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedPrimitiveType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedTypeVariable;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedWildcardType;
import org.checkerframework.framework.type.AnnotatedTypeParameterBounds;
import org.checkerframework.framework.util.AnnotatedTypes;
import org.checkerframework.javacutil.ErrorReporter;
import org.checkerframework.javacutil.TreeUtils;

import java.lang.annotation.Annotation;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Logger;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

import checkers.inference.model.ComparableConstraint;
import checkers.inference.model.ConstantSlot;
import checkers.inference.model.Constraint;
import checkers.inference.model.EqualityConstraint;
import checkers.inference.model.InequalityConstraint;
import checkers.inference.model.RefinementVariableSlot;
import checkers.inference.model.Slot;
import checkers.inference.model.SubtypeConstraint;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;

/**
 * Created by jburke on 3/6/15.
 */
public class InferenceVisitor<Checker extends InferenceChecker,
        Factory extends BaseAnnotatedTypeFactory>
        extends BaseTypeVisitor<Factory> {

    private static final Logger logger = Logger.getLogger(InferenceVisitor.class.getName());

    /* One design alternative would have been to use two separate subclasses instead of the boolean.
     * However, this separates the inference and checking implementation of a method.
     * Using the boolean, the two implementations are closer together.
     *
     */
    protected final boolean infer;

    protected final Checker realChecker;

    public InferenceVisitor(Checker checker, InferenceChecker ichecker, Factory factory, boolean infer) {
        super((infer) ? ichecker : checker, factory);
        this.realChecker = checker;
        this.infer = infer;
    }

    /* Solely sugar */
    protected void addConstraint(final Constraint constraint) {
        InferenceMain.getInstance().getConstraintManager().add(constraint);
    }

    @SuppressWarnings("unchecked")
    @Override
    protected Factory createTypeFactory() {
        return (Factory)((BaseInferrableChecker)checker).getTypeFactory();
    }

    public boolean isValidUse(final AnnotatedDeclaredType declarationType,
                              final AnnotatedDeclaredType useType) {
        // TODO at least for the UTS we don't check annotations on the class declaration
        //   println("InferenceChecker::isValidUse: decl: " + declarationType)
        //   println("InferenceChecker::isValidUse: use: " + useType)

        //TODO JB: Currently visitDeclared strips the useType of it's @VarAnnots etc...
        //TODO JB: So the constraints coming from use don't get passed on via visitParameterizedType->checkTypeArguments

        //TODO JB: At the moment this leads to erroneous subtyping between some type parameter elements,
        //TODO JB: Comment this out and visit CalledMethod.java
        return atypeFactory.getTypeHierarchy().isSubtype(useType.getErased(), declarationType.getErased());
        // return true;
    }

    public void doesNotContain(AnnotatedTypeMirror ty, AnnotationMirror mod, String msgkey, Tree node) {
        doesNotContain(ty, new AnnotationMirror[] {mod}, msgkey, node);
    }

    public void doesNotContain(AnnotatedTypeMirror ty, AnnotationMirror[] mods, String msgkey, Tree node) {
        if (infer) {
            doesNotContainInfer(ty, mods, node);
        } else {
            for (AnnotationMirror mod : mods) {
                if (AnnotatedTypes.containsModifier(ty, mod)) {
                    checker.report(Result.failure(msgkey, ty.getAnnotations().toString(), ty.toString(), node.toString()), node);
                }
            }
        }
    }

    private void doesNotContainInfer(AnnotatedTypeMirror ty, AnnotationMirror[] mods, Tree node) {
        doesNotContainInferImpl(ty, mods, new java.util.LinkedList<AnnotatedTypeMirror>(), node);
    }

    private void doesNotContainInferImpl(AnnotatedTypeMirror ty, AnnotationMirror[] mods,
                                         java.util.List<AnnotatedTypeMirror> visited, Tree node) {
        if (visited.contains(ty)) {
            return;
        }
        visited.add(ty);

        Slot el = InferenceMain.getInstance().getSlotManager().getVariableSlot(ty);

        if (el == null) {
            // TODO: prims not annotated in UTS, others might
            logger.warning("InferenceVisitor::doesNotContain: no annotation in type: " + ty);
        } else {
            if (!InferenceMain.getInstance().isPerformingFlow()) {
                logger.fine("InferenceVisitor::doesNotContain: Inequality constraint constructor invocation(s).");
            }

            for (AnnotationMirror mod : mods) {
                // TODO: are Constants compared correctly???
                addConstraint(new InequalityConstraint(el, new ConstantSlot(mod)));
            }
        }

        if (ty.getKind() == TypeKind.DECLARED) {
            AnnotatedDeclaredType declaredType = (AnnotatedDeclaredType) ty;
            for (AnnotatedTypeMirror typearg : declaredType.getTypeArguments()) {
                doesNotContainInferImpl(typearg, mods, visited, node);
            }
        } else if (ty.getKind() == TypeKind.ARRAY) {
            AnnotatedArrayType arrayType = (AnnotatedArrayType) ty;
            doesNotContainInferImpl(arrayType.getComponentType(), mods, visited, node);
        } else if (ty.getKind() == TypeKind.TYPEVAR) {
            AnnotatedTypeVariable atv = (AnnotatedTypeVariable) ty;
            if (atv.getUpperBound()!=null) {
                doesNotContainInferImpl(atv.getUpperBound(), mods, visited, node);
            }
            if (atv.getLowerBound()!=null) {
                doesNotContainInferImpl(atv.getLowerBound(), mods, visited, node);
            }
        }
    }

    public void mainIs(AnnotatedTypeMirror ty, AnnotationMirror mod, String msgkey, Tree node) {
        if (infer) {
            Slot el = InferenceMain.getInstance().getSlotManager().getVariableSlot(ty);

            if (el == null) {
                // TODO: prims not annotated in UTS, others might
                logger.warning("InferenceVisitor::mainIs: no annotation in type: " + ty);
            } else {
                if (!InferenceMain.getInstance().isPerformingFlow()) {
                    logger.fine("InferenceVisitor::mainIs: Equality constraint constructor invocation(s).");
                    addConstraint(new EqualityConstraint(el, new ConstantSlot(mod)));
                }
            }
        } else {
            if (!ty.hasEffectiveAnnotation(mod)) {
                checker.report(Result.failure(msgkey, ty.getAnnotations().toString(), ty.toString(), node.toString()), node);
            }
        }
    }

    public void mainIsSubtype(AnnotatedTypeMirror ty, AnnotationMirror mod, String msgkey, Tree node) {
        if (infer) {
            Slot el = InferenceMain.getInstance().getSlotManager().getVariableSlot(ty);

            if (el == null) {
                // TODO: prims not annotated in UTS, others might
                logger.warning("InferenceVisitor::mainIs: no annotation in type: " + ty);
            } else {
                if (!InferenceMain.getInstance().isPerformingFlow()) {
                    logger.fine("InferenceVisitor::mainIs: Subtype constraint constructor invocation(s).");
                    addConstraint(new SubtypeConstraint(el, new ConstantSlot(mod)));
                }
            }
        } else {
            if (!ty.hasEffectiveAnnotation(mod)) {
                checker.report(Result.failure(msgkey, ty.getAnnotations().toString(), ty.toString(), node.toString()), node);
            }
        }
    }

    public void mainIsNot(AnnotatedTypeMirror ty, AnnotationMirror mod, String msgkey, Tree node) {
        mainIsNoneOf(ty, new AnnotationMirror[] {mod}, msgkey, node);
    }

    public void mainIsNoneOf(AnnotatedTypeMirror ty, AnnotationMirror[] mods, String msgkey, Tree node) {
        if (infer) {
            Slot el = InferenceMain.getInstance().getSlotManager().getVariableSlot(ty);

            if (el == null) {
                // TODO: prims not annotated in UTS, others might
                logger.warning("InferenceVisitor::isNoneOf: no annotation in type: " + ty);
            } else {
                if (!InferenceMain.getInstance().isPerformingFlow()) {
                    logger.fine("InferenceVisitor::mainIsNoneOf: Inequality constraint constructor invocation(s).");

                    for (AnnotationMirror mod : mods) {
                        addConstraint(new InequalityConstraint(el, new ConstantSlot(mod)));
                    }
                }
            }
        } else {
            for (AnnotationMirror mod : mods) {
                if (ty.hasEffectiveAnnotation(mod)) {
                    checker.report(Result.failure(msgkey, ty.getAnnotations().toString(), ty.toString(), node.toString()), node);
                }
            }
        }
    }



    public void areComparable(AnnotatedTypeMirror ty1, AnnotatedTypeMirror ty2, String msgkey, Tree node) {
        if (infer) {
            final SlotManager slotManager = InferenceMain.getInstance().getSlotManager();
            Slot el1 = slotManager.getVariableSlot(ty1);
            Slot el2 = slotManager.getVariableSlot(ty2);

            if (el1 == null || el2 == null) {
                // TODO: prims not annotated in UTS, others might
                logger.warning("InferenceVisitor::areComparable: no annotation on type: " + ty1 + " or " + ty2);
            } else {
                if (!InferenceMain.getInstance().isPerformingFlow()) {
                    logger.fine("InferenceVisitor::areComparable: Comparable constraint constructor invocation.");
                    addConstraint(new ComparableConstraint(el1, el2));
                }
            }
        } else {
            if (!(atypeFactory.getTypeHierarchy().isSubtype(ty1, ty2) || atypeFactory.getTypeHierarchy().isSubtype(ty2, ty1))) {
                checker.report(Result.failure(msgkey, ty1.toString(), ty2.toString(), node.toString()), node);
            }
        }
    }

    public void areEqual(AnnotatedTypeMirror ty1, AnnotatedTypeMirror ty2, String msgkey, Tree node) {
        if (infer) {
            final SlotManager slotManager = InferenceMain.getInstance().getSlotManager();
            Slot el1 = slotManager.getVariableSlot(ty1);
            Slot el2 = slotManager.getVariableSlot(ty2);

            if (el1 == null || el2 == null) {
                // TODO: prims not annotated in UTS, others might
                logger.warning("InferenceVisitor::areEqual: no annotation on type: " + ty1 + " or " + ty2);
            } else {
                if (!InferenceMain.getInstance().isPerformingFlow()) {
                    logger.fine("InferenceVisitor::areEqual: Equality constraint constructor invocation.");
                    addConstraint(new EqualityConstraint(el1, el2));
                }
            }
        } else {
            if (!ty1.equals(ty2)) {
                checker.report(Result.failure(msgkey, ty1.toString(), ty2.toString(), node.toString()), node);
            }
        }
    }

    protected void checkTypeArguments(Tree toptree,
                                      List<? extends AnnotatedTypeParameterBounds> paramBounds,
                                      List<? extends AnnotatedTypeMirror> typeargs,
                                      List<? extends Tree> typeargTrees) {
        // System.out.printf("BaseTypeVisitor.checkTypeArguments: %s, TVs: %s, TAs: %s, TATs: %s\n",
        //         toptree, paramBounds, typeargs, typeargTrees);

        // If there are no type variables, do nothing.
        if (paramBounds.isEmpty())
            return;

        assert paramBounds.size() == typeargs.size() :
                "BaseTypeVisitor.checkTypeArguments: mismatch between type arguments: " +
                        typeargs + " and type parameter bounds" + paramBounds;

        Iterator<? extends AnnotatedTypeParameterBounds> boundsIter = paramBounds.iterator();
        Iterator<? extends AnnotatedTypeMirror> argIter = typeargs.iterator();

        while (boundsIter.hasNext()) {

            AnnotatedTypeParameterBounds bounds = boundsIter.next();
            AnnotatedTypeMirror typeArg = argIter.next();

            AnnotatedTypeMirror varUpperBound = bounds.getUpperBound();
            final AnnotatedTypeMirror typeArgForUpperBoundCheck = typeArg;

            if (typeArg.getKind() == TypeKind.WILDCARD ) {

                if (bounds.getUpperBound().getKind() == TypeKind.WILDCARD) {
                    //TODO: When capture conversion is implemented, this special case should be removed.
                    //TODO: This may not occur only in places where capture conversion occurs but in those cases
                    //TODO: The containment check provided by this method should be enough
                    continue;
                }

                //If we have a declaration:
                // class MyClass<T extends String> ...
                //
                //the javac compiler allows wildcard type arguments that have Java types OUTSIDE of the
                //bounds of T, i.e:
                // MyClass<? extends Object>
                //
                //This is sound because every NON-WILDCARD reference to MyClass MUST obey those bounds
                //This leads to cases where varUpperBound is actually a subtype of typeArgForUpperBoundCheck
                final TypeMirror varUnderlyingUb = varUpperBound.getUnderlyingType();
                final TypeMirror argUnderlyingUb = ((AnnotatedWildcardType)typeArg).getExtendsBound().getUnderlyingType();
                if ( !types.isSubtype(argUnderlyingUb, varUnderlyingUb)
                        &&  types.isSubtype(varUnderlyingUb, argUnderlyingUb)) {
                    varUpperBound = AnnotatedTypes.asSuper(types, atypeFactory,
                            varUpperBound, typeArgForUpperBoundCheck);
                }
            }

            if (typeargTrees == null || typeargTrees.isEmpty()) {
                // The type arguments were inferred and we mark the whole method.
                // The inference fails if we provide invalid arguments,
                // therefore issue an error for the arguments.
                // I hope this is less confusing for users.
                commonAssignmentCheck(varUpperBound,
                        typeArg, toptree,
                        "type.argument.type.incompatible", false);
            } else {
                commonAssignmentCheck(varUpperBound, typeArg,
                        typeargTrees.get(typeargs.indexOf(typeArg)),
                        "type.argument.type.incompatible", false);
            }

            if (!atypeFactory.getTypeHierarchy().isSubtype(bounds.getLowerBound(), typeArg)) {
                if (typeargTrees == null || typeargTrees.isEmpty()) {
                    // The type arguments were inferred and we mark the whole method.
                    checker.report(Result.failure("type.argument.type.incompatible",
                                    typeArg, bounds),
                            toptree);
                } else {
                    checker.report(Result.failure("type.argument.type.incompatible",
                                    typeArg, bounds),
                            typeargTrees.get(typeargs.indexOf(typeArg)));
                }
            }
        }
    }

    /**
     * Checks the validity of an assignment (or pseudo-assignment) from a value
     * to a variable and emits an error message (through the compiler's
     * messaging interface) if it is not valid.
     *
     * @param varTree the AST node for the variable
     * @param valueExp the AST node for the value
     * @param errorKey the error message to use if the check fails (must be a
     *        compiler message key, see {@link org.checkerframework.checker.compilermsgs.qual.CompilerMessageKey})
     */
    @Override
    protected void commonAssignmentCheck(Tree varTree, ExpressionTree valueExp,
            /*@CompilerMessageKey*/ String errorKey) {
        if (!validateTypeOf(varTree)) {
            return;
        }

        // commonAssignmentCheck eventually create an equality constraint between varTree and valueExp.
        // For inference, we need this constraint to be between the RefinementVariable and the value.
        // Refinement variables come from flow inference, so we need to call getAnnotatedType instead of getDefaultedAnnotatedType
        AnnotatedTypeMirror var;
        if (this.infer) {
            var = atypeFactory.getAnnotatedType(varTree);
        } else {
            var = atypeFactory.getDefaultedAnnotatedType(varTree, valueExp);
        }

        assert var != null : "no variable found for tree: " + varTree;

        checkAssignability(var, varTree);

        boolean isLocalVariableAssignment = false;
        if (varTree instanceof AssignmentTree) {
            Tree rhs = ((AssignmentTree) varTree).getVariable();
            isLocalVariableAssignment = rhs instanceof IdentifierTree
                    && !TreeUtils.isFieldAccess(rhs);
        }
        if (varTree instanceof VariableTree) {
            isLocalVariableAssignment = TreeUtils.enclosingMethod(getCurrentPath()) != null;
        }

        commonAssignmentCheck(var, valueExp, errorKey,
                isLocalVariableAssignment);
    }

    @Override
    protected void commonAssignmentCheck(AnnotatedTypeMirror varType,
                                         AnnotatedTypeMirror valueType, Tree valueTree, /*@CompilerMessageKey*/ String errorKey,
                                         boolean isLocalVariableAssignement) {

        String valueTypeString = valueType.toString();
        String varTypeString = varType.toString();

        // If both types as strings are the same, try outputting
        // the type including also invisible qualifiers.
        // This usually means there is a mistake in type defaulting.
        // This code is therefore not covered by a test.
        if (valueTypeString.equals(varTypeString)) {
            valueTypeString = valueType.toString(true);
            varTypeString = varType.toString(true);
        }

        if (isLocalVariableAssignement && varType.getKind() == TypeKind.TYPEVAR
                && varType.getAnnotations().isEmpty()) {
            // If we have an unbound local variable that is a type variable,
            // then we allow the assignment.
            return;
        }

        if (checker.hasOption("showchecks")) {
            long valuePos = positions.getStartPosition(root, valueTree);
            System.out.printf(
                    " %s (line %3d): %s %s%n     actual: %s %s%n   expected: %s %s%n",
                    "About to test whether actual is a subtype of expected",
                    (root.getLineMap() != null ? root.getLineMap().getLineNumber(valuePos) : -1),
                    valueTree.getKind(), valueTree,
                    valueType.getKind(), valueTypeString,
                    varType.getKind(), varTypeString);
        }

        // Handle refinement variables.
        // If this is the result of an assignment,
        // instead of a subtype relationship we know the refinement variable
        // on the LHS must be equal to the variable on the RHS.
        boolean success = true;
        boolean inferenceRefinementVariable = false;
        if (infer) {
            inferenceRefinementVariable = maybeAddRefinementVariableConstraints(varType, valueType);
        }

        if (!inferenceRefinementVariable) {   //TODO: DOES THIS IGNORE NESTED ANNOTATIONS THAT SHOULD HAVE CONSTRAINTS AGAINST EACH OTHER?
            //TODO: @Ref List<@5 String> a = new ArrayList<@6 String>(); //e.g. @6 == @5 won't be generated?
            //TODO: FIND A GENERAL CHECKER FRAMEWORK LOCATION TO APPROPRIATELY BOX
            if (varType.getKind() == TypeKind.DECLARED && valueType.getKind().isPrimitive()) {
                success = atypeFactory.getTypeHierarchy().isSubtype(atypeFactory.getBoxedType((AnnotatedPrimitiveType) valueType), varType);
            } else {
                success = atypeFactory.getTypeHierarchy().isSubtype(valueType, varType);
            }
        }

        // TODO: integrate with subtype test.
        if (success) {
            for (Class<? extends Annotation> mono : atypeFactory.getSupportedMonotonicTypeQualifiers()) {
                if (valueType.hasAnnotation(mono)
                        && varType.hasAnnotation(mono)) {
                    checker.report(
                            Result.failure("monotonic.type.incompatible",
                                    mono.getCanonicalName(),
                                    mono.getCanonicalName(),
                                    valueType.toString()), valueTree);
                    return;
                }
            }
        }

        if (checker.hasOption("showchecks")) {
            long valuePos = positions.getStartPosition(root, valueTree);
            System.out.printf(
                    " %s (line %3d): %s %s%n     actual: %s %s%n   expected: %s %s%n",
                    (success ? "success: actual is subtype of expected" : "FAILURE: actual is not subtype of expected"),
                    (root.getLineMap() != null ? root.getLineMap().getLineNumber(valuePos) : -1),
                    valueTree.getKind(), valueTree,
                    valueType.getKind(), valueTypeString,
                    varType.getKind(), varTypeString);
        }

        // Use an error key only if it's overridden by a checker.
        if (!success) {
            checker.report(Result.failure(errorKey,
                    valueTypeString, varTypeString), valueTree);
        }
    }

    private void addRefinementVariableConstraints(final AnnotatedTypeMirror varType,
                                                  final AnnotatedTypeMirror valueType,
                                                  final SlotManager slotManager,
                                                  final ConstraintManager constraintManager) {
        Slot sup = slotManager.getVariableSlot(varType);
        Slot sub = slotManager.getVariableSlot(valueType);
        logger.fine("InferenceVisitor::commonAssignmentCheck: Equality constraint for qualifiers sub: " + sub + " sup: " + sup);

        // Equality between the refvar and the value
        constraintManager.add(new EqualityConstraint(sup, sub));

        // Refinement variable still needs to be a subtype of its declared type value
        constraintManager.add(new SubtypeConstraint(sup, ((RefinementVariableSlot) sup).getRefined()));
    }

    public boolean maybeAddRefinementVariableConstraints(final AnnotatedTypeMirror varType, final AnnotatedTypeMirror valueType) {
        boolean inferenceRefinementVariable = false;
        final SlotManager slotManager = InferenceMain.getInstance().getSlotManager();
        final ConstraintManager constraintManager = InferenceMain.getInstance().getConstraintManager();

        if(varType.getKind() == TypeKind.TYPEVAR) {
            if(valueType.getKind() == TypeKind.TYPEVAR ) {
                final AnnotatedTypeVariable varTypeTv = (AnnotatedTypeVariable) varType;

                final AnnotatedTypeMirror varUpperBoundAtm;
                final AnnotatedTypeMirror varLowerBoundAtm;

                try {
                    varUpperBoundAtm = InferenceUtil.findUpperBoundType(varTypeTv);
                    varLowerBoundAtm = InferenceUtil.findLowerBoundType(varTypeTv);

                } catch(Throwable exc) {
                    if (InferenceMain.isHackMode()) {
                        return false;
                    } else {
                        throw exc;
                    }
                }

                final Slot upperBoundSlot = slotManager.getVariableSlot(varUpperBoundAtm);
                final Slot lowerBoundSlot = slotManager.getVariableSlot(varLowerBoundAtm);
                if(upperBoundSlot instanceof RefinementVariableSlot && lowerBoundSlot instanceof RefinementVariableSlot) {
                    final AnnotatedTypeVariable valueTypeTv = (AnnotatedTypeVariable) valueType;
                    final AnnotatedTypeMirror valUpperBoundAtm;
                    final AnnotatedTypeMirror valLowerBoundAtm;
                    try {
                        valUpperBoundAtm = InferenceUtil.findUpperBoundType(valueTypeTv);
                        valLowerBoundAtm = InferenceUtil.findLowerBoundType(valueTypeTv);
                    } catch(Throwable exc) {
                        if (InferenceMain.isHackMode()) {
                            return false;
                        } else {
                            throw exc;
                        }
                    }
                    addRefinementVariableConstraints(varUpperBoundAtm, valUpperBoundAtm, slotManager, constraintManager);

                    constraintManager.add(new EqualityConstraint(lowerBoundSlot, slotManager.getVariableSlot(valLowerBoundAtm)));
                    constraintManager.add(new SubtypeConstraint(lowerBoundSlot, upperBoundSlot));

                    inferenceRefinementVariable = true;
                }

            } else if (valueType.getKind() == TypeKind.NULL) {
                //TODO: For now do nothing but we should be doing some refinement

            } else {
                if (!InferenceMain.isHackMode()) {
                    ErrorReporter.errorAbort("Unexpected assignment to type variable"); //TODO: Either more detail, or remove because of type args?
                    //TODO: OR A DIFFERENT SET OF CONSTRAINTS?
                }
            }
        } else {

            //TODO: RECONSIDER THIS WHEN WE CONSIDER WILDCARDS
            if (varType.getKind() != TypeKind.WILDCARD) {
                Slot sup = InferenceMain.getInstance().getSlotManager().getVariableSlot(varType);
                if (sup instanceof RefinementVariableSlot && !InferenceMain.getInstance().isPerformingFlow()) {
                    inferenceRefinementVariable = true;
                    Slot sub = slotManager.getVariableSlot(valueType);
                    logger.fine("InferenceVisitor::commonAssignmentCheck: Equality constraint for qualifiers sub: " + sub + " sup: " + sup);

                    // Equality between the refvar and the value
                    constraintManager.add(new EqualityConstraint(sup, sub));

                    // Refinement variable still needs to be a subtype of its declared type value
                    constraintManager.add(new SubtypeConstraint(sup, ((RefinementVariableSlot) sup).getRefined()));
                }
            }
        }

        return inferenceRefinementVariable;
    }

    //TODO: WE NEED TO FIX this method and have it do something sensible
    //TODO: The issue here is that I have removed the error reporting from this method
    //TODO: In order to allow verigames to move forward.
    /**
     * Tests whether the tree expressed by the passed type tree is a valid type,
     * and emits an error if that is not the case (e.g. '@Mutable String').
     * If the tree is a method or constructor, check the return type.
     *
     * @param tree  the AST type supplied by the user
     */
    @Override
    public boolean validateTypeOf(Tree tree) {
        AnnotatedTypeMirror type;
        // It's quite annoying that there is no TypeTree
        switch (tree.getKind()) {
            case PRIMITIVE_TYPE:
            case PARAMETERIZED_TYPE:
            case TYPE_PARAMETER:
            case ARRAY_TYPE:
            case UNBOUNDED_WILDCARD:
            case EXTENDS_WILDCARD:
            case SUPER_WILDCARD:
            case ANNOTATED_TYPE:
                type = atypeFactory.getAnnotatedTypeFromTypeTree(tree);
                break;
            case METHOD:
                type = atypeFactory.getMethodReturnType((MethodTree) tree);
                if (type == null ||
                        type.getKind() == TypeKind.VOID) {
                    // Nothing to do for void methods.
                    // Note that for a constructor the AnnotatedExecutableType does
                    // not use void as return type.
                    return true;
                }
                break;
            default:
                type = atypeFactory.getAnnotatedType(tree);
        }

        // basic consistency checks
        if (!AnnotatedTypes.isValidType(atypeFactory.getQualifierHierarchy(), type)) {
//            checker.report(Result.failure("type.invalid", type.getAnnotations(),
//                    type.toString()), tree);
//            return false;
            return true;
        }

        //TODO: THIS MIGHT FAIL
//        typeValidator.isValid(type, tree);
        // more checks (also specific to checker, potentially)
        return true;
    }

    @Override
    protected InferenceValidator createTypeValidator() {
        return new InferenceValidator(checker, this, atypeFactory);
    }
}
