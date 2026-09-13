package io.titan.transpiler.tir;

public sealed interface DeclarationNode extends TirNode permits DeclareVariable, DeclareCursor, DeclareHandler {
}
