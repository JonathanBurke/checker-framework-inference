package checkers.inference;

import org.checkerframework.common.basetype.BaseAnnotatedTypeFactory;
import org.checkerframework.framework.flow.CFAnalysis;
import org.checkerframework.framework.flow.CFStore;
import org.checkerframework.framework.flow.CFTransfer;
import org.checkerframework.framework.flow.CFValue;
import org.checkerframework.framework.type.GenericAnnotatedTypeFactory;
import org.checkerframework.javacutil.Pair;

import java.util.List;

import javax.lang.model.element.VariableElement;

import checkers.inference.dataflow.InferenceAnalysis;
import checkers.inference.dataflow.InferenceTransfer;

import com.sun.source.tree.Tree;
import com.sun.source.util.Trees;

/**
 * BaseInferrableChecker is the abstract base class that all inference checkers should inherit from.
 * It extends BaseTypeChecker through InferenceChecker. It differs from BaseTypeChecker in that
 * it provides the scaffolding for configuring the system between inference/typechecking mode.  In
 * inference mode, these methods are used by InferenceMain to configure the framework.
 * In typecheck mode, configuration works like the Checker Framework in general (i.e. the SourceChecker
 * and BaseTypeChecker configure the system).
 *
 * See the interface InferrableChecker for descriptions of the methods that are necessary to make inference work.
 */
public abstract class BaseInferrableChecker extends InferenceChecker implements InferrableChecker {

    @Override
    public void initChecker() {
        //In between these brackets, is code copied directly from SourceChecker
        //except for the last line assigning the visitor
        {
            Trees trees = Trees.instance(processingEnv);
            assert( trees != null ); /*nninvariant*/
            this.trees = trees;

            this.messager = processingEnv.getMessager();
            this.messages = getMessages();

            this.visitor = createVisitor(null, createRealTypeFactory(), false);
        }
    }

    @Override
    public InferenceVisitor<?, ?> createVisitor(InferenceChecker ichecker, BaseAnnotatedTypeFactory factory, boolean infer) {
        return new InferenceVisitor<>(this, ichecker, factory, infer);
    }

    /**
     * The "real" type factory is the one that contains the type hierarchy for which we are inferring annotations
     * (e.g. OsTrustedTypeFactory rather InferenceTypeFactory).  In typechecking mode, the real type factory
     * is the only type factory created.  In inference mode, the InferenceAnnotatedTypeFactory is primarily
     * used though it will make calls to the real type factory when needed.
     */
    @Override
    public BaseAnnotatedTypeFactory createRealTypeFactory() {
        return new BaseAnnotatedTypeFactory(this);
    }

    @Override
    public CFAnalysis createInferenceAnalysis(
                    InferenceChecker checker,
                    GenericAnnotatedTypeFactory<CFValue, CFStore, CFTransfer, CFAnalysis> factory,
                    List<Pair<VariableElement, CFValue>> fieldValues,
                    SlotManager slotManager,
                    ConstraintManager constraintManager,
                    InferrableChecker realChecker) {

        return new InferenceAnalysis(checker, factory, fieldValues, slotManager, constraintManager, realChecker);
    }

    @Override
    public CFTransfer createInferenceTransferFunction(InferenceAnalysis analysis) {
        return new InferenceTransfer(analysis);
    }

    @Override
    public boolean withCombineConstraints() {
        return false;
    }

    @Override
    public boolean isConstant(Tree node) {
        return false;
    }
}
