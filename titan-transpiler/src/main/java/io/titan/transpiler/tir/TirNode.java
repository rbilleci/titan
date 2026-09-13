package io.titan.transpiler.tir;

/**
 * Base node for Titan Intermediate Representation (TIR).
 */
public sealed interface TirNode permits DeclarationNode, StatementNode, SqlNode, ExpressionNode {
    <R> R accept(TirVisitor<R> visitor);
}
