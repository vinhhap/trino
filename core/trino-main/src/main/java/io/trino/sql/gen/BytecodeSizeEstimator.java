/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.sql.gen;

import io.airlift.bytecode.BytecodeNode;
import io.airlift.bytecode.MethodGenerationContext;
import io.airlift.bytecode.Scope;
import org.objectweb.asm.commons.CodeSizeEvaluator;

// TODO: move to the airlift bytecode library
public final class BytecodeSizeEstimator
{
    private BytecodeSizeEstimator() {}

    /**
     * Estimates an upper bound of the code bytes {@code node} contributes when generated
     * into a method with the given scope. The scope must already declare every variable
     * the node references.
     * <p>
     * The dry run is side effect free: {@link CodeSizeEvaluator} without a delegate only
     * accumulates instruction sizes and never resolves ASM labels, so the node can still
     * be generated into a real method afterwards.
     */
    public static int estimateMaxCodeSize(BytecodeNode node, Scope scope)
    {
        CodeSizeEvaluator evaluator = new CodeSizeEvaluator(null);
        MethodGenerationContext context = new MethodGenerationContext(evaluator);
        context.enterScope(scope);
        node.accept(evaluator, context);
        context.exitScope(scope);
        return evaluator.getMaxSize();
    }
}
