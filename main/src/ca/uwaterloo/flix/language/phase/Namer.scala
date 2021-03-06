/*
 * Copyright 2015-2016 Magnus Madsen
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ca.uwaterloo.flix.language.phase

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.Ast.Source
import ca.uwaterloo.flix.language.ast.WeededAst.{ChoicePattern, TypeParams}
import ca.uwaterloo.flix.language.ast._
import ca.uwaterloo.flix.language.errors.NameError
import ca.uwaterloo.flix.util.Validation._
import ca.uwaterloo.flix.util.collection.MultiMap
import ca.uwaterloo.flix.util.{InternalCompilerException, Validation}

import scala.collection.mutable

/**
  * The Namer phase introduces unique symbols for each syntactic entity in the program.
  */
object Namer extends Phase[WeededAst.Program, NamedAst.Root] {

  /**
    * Introduces unique names for each syntactic entity in the given `program`.
    **/
  def run(program: WeededAst.Program)(implicit flix: Flix): Validation[NamedAst.Root, NameError] = flix.phase("Namer") {
    // compute all the source locations
    val locations = program.roots.foldLeft(Map.empty[Source, SourceLocation]) {
      case (macc, root) => macc + (root.loc.source -> root.loc)
    }

    // make an empty program to fold over.
    val prog0 = NamedAst.Root(
      classes = Map.empty,
      sigs = Map.empty,
      instances = Map.empty,
      defs = Map.empty,
      enums = Map.empty,
      typealiases = Map.empty,
      latticesOps = Map.empty,
      properties = Map.empty,
      reachable = program.reachable,
      sources = locations
    )

    // collect all the declarations.
    val declarations = mapN(traverse(program.roots) {
      case root => mapN(mergeUseEnvs(root.uses, UseEnv.empty)) {
        case uenv0 => root.decls.map(d => (uenv0, d))
      }
    })(_.flatten)

    // fold over the top-level declarations.
    flatMapN(declarations) {
      case decls => Validation.fold(decls, prog0) {
        case (pacc, (uenv0, decl)) => visitDecl(decl, Name.RootNS, uenv0, pacc)
      }
    }
  }

  /**
    * Performs naming on the given declaration `decl0` in the given namespace `ns0` under the given (partial) program `prog0`.
    */
  private def visitDecl(decl0: WeededAst.Declaration, ns0: Name.NName, uenv0: UseEnv, prog0: NamedAst.Root)(implicit flix: Flix): Validation[NamedAst.Root, NameError] = decl0 match {
    /*
     * Namespace.
     */
    case WeededAst.Declaration.Namespace(ns, uses, decls, loc) =>
      mergeUseEnvs(uses, uenv0) flatMap {
        newEnv => Validation.fold(decls, prog0) {
          case (pacc, decl) =>
            val namespace = Name.NName(ns.sp1, ns0.idents ::: ns.idents, ns.sp2)
            visitDecl(decl, namespace, newEnv, pacc)
        }
    }

    case decl@WeededAst.Declaration.Class(doc, mod, ident, tparam, sigs, loc) =>
      // Check if the class already exists.
      val classes = prog0.classes.getOrElse(ns0, Map.empty)
      val sigs = prog0.sigs.getOrElse(ns0, Map.empty)
      classes.get(ident.name) match {
        case None =>
          // Case 1: The class does not already exist. Update it.
          visitClass(decl, uenv0, Map.empty, ns0) map {
            case clazz@NamedAst.Class(_, _, _, _, sigs0, _) =>
              prog0.copy(
                classes = prog0.classes + (ns0 -> (classes + (ident.name -> clazz))),
                sigs = prog0.sigs + (ns0 -> (sigs ++ sigs0.map(sig => (sig.sym.name, sig)).toMap))
              )
          }
        case Some(clazz) =>
          // Case 2: Duplicate class.
          NameError.DuplicateClass(ident.name, clazz.sym.loc, ident.loc).toFailure
      }

    case decl@WeededAst.Declaration.Instance(doc, mod, clazz, tpe0, defs, loc) =>
      // duplication check must come after name resolution
      val instances = prog0.instances.getOrElse(ns0, MultiMap.empty)
      visitInstance(decl, uenv0, Map.empty, ns0) map {
        instance => prog0.copy(instances = prog0.instances + (ns0 -> (instances + (clazz.ident.name, instance)))) // MATT fix to be global map
      }

    /*
     * Definition.
     */
    case decl@WeededAst.Declaration.Def(doc, ann, mod, ident, tparams0, fparams0, exp, tpe, eff0, loc) =>
      // Check if the definition already exists.
      val defns = prog0.defs.getOrElse(ns0, Map.empty)
      defns.get(ident.name) match {
        case None =>
          // Case 1: The definition does not already exist. Update it.
          visitDef(decl, uenv0, Map.empty, ns0) map {
            case defn => prog0.copy(defs = prog0.defs + (ns0 -> (defns + (ident.name -> defn))))
          }
        case Some(defn) =>
          // Case 2: Duplicate definition.
          NameError.DuplicateDef(ident.name, defn.loc, ident.loc).toFailure
      }

    /*
     * Law.
     */
    case WeededAst.Declaration.Law(doc, ann, mod, ident, tparams0, fparams0, exp, tpe, eff0, loc) => ??? // TODO

    /*
     * Enum.
     */
    case WeededAst.Declaration.Enum(doc, mod, ident, tparams0, cases, loc) =>
      val enums0 = prog0.enums.getOrElse(ns0, Map.empty)
      enums0.get(ident.name) match {
        case None =>
          // Case 2.1: The enum does not exist in the namespace. Update it.
          val sym = Symbol.mkEnumSym(ns0, ident)

          // Compute the type parameters.
          flatMapN(getTypeParamsFromCases(tparams0, cases.values.toList, loc)) {
            tparams =>

              // Compute the kind of the enum.
              val kind = Kind.mkArrow(tparams.length)

              val tenv = tparams.map(kv => kv.name.name -> kv.tpe).toMap
              val quantifiers = tparams.map(_.tpe).map(x => NamedAst.Type.Var(x, loc))
              val enumType = if (quantifiers.isEmpty)
                NamedAst.Type.Enum(sym, kind)
              else {
                val base = NamedAst.Type.Enum(sym, kind)
                quantifiers.foldLeft(base: NamedAst.Type) {
                  case (tacc, tvar) => NamedAst.Type.Apply(tacc, tvar, loc)
                }
              }

              mapN(casesOf(cases, uenv0, tenv)) {
                case cases =>
                  val enum = NamedAst.Enum(doc, mod, sym, tparams, cases, enumType, kind, loc)
                  val enums = enums0 + (ident.name -> enum)
                  prog0.copy(enums = prog0.enums + (ns0 -> enums))
              }
          }
        case Some(enum) =>
          // Case 2.2: Duplicate definition.
          NameError.DuplicateDef(ident.name, enum.sym.loc, ident.loc).toFailure
      }

    /*
     * Type Alias.
     */
    case WeededAst.Declaration.TypeAlias(doc, mod, ident, tparams0, tpe0, loc) =>
      val typealiases0 = prog0.typealiases.getOrElse(ns0, Map.empty)
      typealiases0.get(ident.name) match {
        case None =>
          // Case 1: The type alias does not exist in the namespace. Add it.
          flatMapN(getTypeParamsFromFormalParams(tparams0, List.empty, tpe0, loc, allowElision = false)) {
            tparams =>
              val tenv = getTypeEnv(tparams)
              mapN(visitType(tpe0, uenv0, tenv)) {
                case tpe =>
                  val sym = Symbol.mkTypeAliasSym(ns0, ident)
                  val typealias = NamedAst.TypeAlias(doc, mod, sym, tparams, tpe, loc)
                  val typealiases = typealiases0 + (ident.name -> typealias)
                  prog0.copy(typealiases = prog0.typealiases + (ns0 -> typealiases))
              }
          }
        case Some(typealias) =>
          // Case 2: Duplicate type alias.
          NameError.DuplicateTypeAlias(ident.name, typealias.sym.loc, ident.loc).toFailure
      }

    /*
     * Property.
     */
    case WeededAst.Declaration.Property(law, defn, exp0, loc) =>
      visitExp(exp0, Map.empty, uenv0, Map.empty) map {
        case exp =>
          val lawSym = Symbol.mkDefnSym(law.namespace, law.ident)
          val defnSym = Symbol.mkDefnSym(ns0, defn)
          val property = NamedAst.Property(lawSym, defnSym, exp, loc)
          val properties = prog0.properties.getOrElse(ns0, Nil)
          prog0.copy(properties = prog0.properties + (ns0 -> (property :: properties)))
      }

    /*
     * BoundedLattice (deprecated).
     */
    case WeededAst.Declaration.LatticeOps(tpe0, bot0, top0, equ0, leq0, lub0, glb0, loc) =>
      val botVal = visitExp(bot0, Map.empty, uenv0, Map.empty)
      val topVal = visitExp(top0, Map.empty, uenv0, Map.empty)
      val equVal = visitExp(equ0, Map.empty, uenv0, Map.empty)
      val leqVal = visitExp(leq0, Map.empty, uenv0, Map.empty)
      val lubVal = visitExp(lub0, Map.empty, uenv0, Map.empty)
      val glbVal = visitExp(glb0, Map.empty, uenv0, Map.empty)
      val tpeVal = visitType(tpe0, uenv0, Map.empty)

      mapN(botVal, topVal, equVal, leqVal, lubVal, glbVal, tpeVal) {
        case (bot, top, equ, leq, lub, glb, tpe) =>
          val lattice = NamedAst.LatticeOps(tpe, bot, top, equ, leq, lub, glb, ns0, loc)
          prog0.copy(latticesOps = prog0.latticesOps + (tpe -> lattice)) // NB: This just overrides any existing binding.
      }

    case WeededAst.Declaration.Sig(doc, ann, mod, ident, tparams, fparams, tpe, eff, loc) =>
      throw InternalCompilerException("Unexpected signature declaration.") // signatures should not be at the top level

  }

  /**
    * Performs naming on the given constraint `c0` under the given environments `env0`, `uenv0`, and `tenv0`.
    */
  private def visitConstraint(c0: WeededAst.Constraint, outerEnv: Map[String, Symbol.VarSym], uenv0: UseEnv, tenv0: Map[String, Type.Var])(implicit flix: Flix): Validation[NamedAst.Constraint, NameError] = c0 match {
    case WeededAst.Constraint(h, bs, loc) =>
      // Find the variables visible in the head and rule scope of the constraint.
      // Remove any variables already in the outer environment.
      val headVars = bs.flatMap(visibleInHeadScope).filterNot(ident => outerEnv.contains(ident.name))
      val ruleVars = bs.flatMap(visibleInRuleScope).filterNot(ident => outerEnv.contains(ident.name))

      // Introduce a symbol for each variable that is visible in the head scope of the constraint (excluding those visible by the rule scope).
      val headEnv = headVars.foldLeft(Map.empty[String, Symbol.VarSym]) {
        case (macc, ident) => macc.get(ident.name) match {
          // Check if the identifier is bound by the rule scope.
          case None if !ruleVars.exists(_.name == ident.name) =>
            macc + (ident.name -> Symbol.freshVarSym(ident))
          case _ => macc
        }
      }

      // Introduce a symbol for each variable that is visible in the rule scope of the constraint.
      val ruleEnv = ruleVars.foldLeft(Map.empty[String, Symbol.VarSym]) {
        case (macc, ident) => macc.get(ident.name) match {
          case None => macc + (ident.name -> Symbol.freshVarSym(ident))
          case Some(sym) => macc
        }
      }

      // Perform naming on the head and body predicates.
      mapN(visitHeadPredicate(h, outerEnv, headEnv, ruleEnv, uenv0, tenv0), traverse(bs)(b => visitBodyPredicate(b, outerEnv, headEnv, ruleEnv, uenv0, tenv0))) {
        case (head, body) =>
          val headParams = headEnv.map {
            case (_, sym) => NamedAst.ConstraintParam.HeadParam(sym, sym.tvar, sym.loc)
          }
          val ruleParam = ruleEnv.map {
            case (_, sym) => NamedAst.ConstraintParam.RuleParam(sym, sym.tvar, sym.loc)
          }
          val cparams = (headParams ++ ruleParam).toList
          NamedAst.Constraint(cparams, head, body, loc)
      }
  }

  /**
    * Performs naming on the given `cases` map.
    */
  private def casesOf(cases: Map[String, WeededAst.Case], uenv0: UseEnv, tenv0: Map[String, Type.Var])(implicit flix: Flix): Validation[Map[String, NamedAst.Case], NameError] = {
    val casesVal = cases map {
      case (name, WeededAst.Case(enum, tag, tpe)) =>
        mapN(visitType(tpe, uenv0, tenv0)) {
          case t => (name, NamedAst.Case(enum, tag, t))
        }
    }
    mapN(sequence(casesVal))(_.toMap)
  }

  /**
    * Performs naming on the given class `clazz`.
    */
  private def visitClass(clazz: WeededAst.Declaration.Class, uenv0: UseEnv, tenv0: Map[String, Type.Var], ns0: Name.NName)(implicit flix: Flix): Validation[NamedAst.Class, NameError] = clazz match {
    case WeededAst.Declaration.Class(doc, mod, ident, tparam, signatures, loc) =>
      val sym = Symbol.mkClassSym(ns0, ident)
      val className = Name.mkQName(ident)
      val tparams = getProperTypeParams(tparam).head // only 1 tparam allowed for now
      for {
        sigs <- traverse(signatures)(visitSig(_, uenv0, tenv0, ns0, className, sym, tparams))
      } yield NamedAst.Class(doc, mod, sym, tparams, sigs, loc)
  }

  /**
    * Performs naming on the given instance `instance`.
    */
  private def visitInstance(instance: WeededAst.Declaration.Instance, uenv0: UseEnv, tenv0: Map[String, Type.Var], ns0: Name.NName)(implicit flix: Flix): Validation[NamedAst.Instance, NameError] = instance match {
    case WeededAst.Declaration.Instance(doc, mod, clazz, tpe, defs0, loc) =>
      for {
        tpe <- visitType(tpe, uenv0, tenv0)
        defs <- traverse(defs0)(visitDef(_, uenv0, tenv0, ns0))
      } yield NamedAst.Instance(doc, mod, clazz, tpe, defs, loc)
  }

  private def visitSig(sig: WeededAst.Declaration.Sig, uenv0: UseEnv, tenv0: Map[String, Type.Var], ns0: Name.NName, className: Name.QName, classSym: Symbol.ClassSym, classTparam: NamedAst.TypeParam)(implicit flix: Flix): Validation[NamedAst.Sig, NameError] = sig match {
    case WeededAst.Declaration.Sig(doc, ann, mod, ident, tparams0, fparams0, tpe, eff0, loc) =>
      flatMapN(getTypeParamsFromFormalParams(tparams0, fparams0, tpe, loc, allowElision = true)) {
        tparams =>
          val tenv = tenv0 ++ getTypeEnv(tparams)
          flatMapN(getFormalParams(fparams0, uenv0, tenv)) {
            case fparams =>
              val env0 = getVarEnv(fparams)
              val annVal = traverse(ann)(visitAnnotation(_, env0, uenv0, tenv))
              val schemeVal = getSigScheme(tparams, tpe, uenv0, tenv, className, classTparam)
              val tpeVal = visitType(eff0, uenv0, tenv)
              mapN(annVal, schemeVal, tpeVal) {
                case (as, sc, eff) =>
                  val sym = Symbol.mkSigSym(classSym, ident)
                  NamedAst.Sig(doc, as, mod, sym, tparams, fparams, sc, eff, loc)
              }
          }
      }
  }
  /**
    * Performs naming on the given definition declaration `decl0` under the given environments `env0`, `uenv0`, and `tenv0`.
    */
  private def visitDef(decl0: WeededAst.Declaration.Def, uenv0: UseEnv, tenv0: Map[String, Type.Var], ns0: Name.NName)(implicit flix: Flix): Validation[NamedAst.Def, NameError] = decl0 match {
    case WeededAst.Declaration.Def(doc, ann, mod, ident, tparams0, fparams0, exp, tpe, eff0, loc) =>
      flatMapN(getTypeParamsFromFormalParams(tparams0, fparams0, tpe, loc, allowElision = true)) {
        tparams =>
          val tenv = tenv0 ++ getTypeEnv(tparams)
          flatMapN(getFormalParams(fparams0, uenv0, tenv)) {
            case fparams =>
              val env0 = getVarEnv(fparams)
              val annVal = traverse(ann)(visitAnnotation(_, env0, uenv0, tenv))
              val expVal = visitExp(exp, env0, uenv0, tenv)
              val schemeVal = getDefScheme(tparams, tpe, uenv0, tenv)
              val tpeVal = visitType(eff0, uenv0, tenv)
              mapN(annVal, expVal, schemeVal, tpeVal) {
                case (as, e, sc, eff) =>
                  val sym = Symbol.mkDefnSym(ns0, ident)
                  NamedAst.Def(doc, as, mod, sym, tparams, fparams, e, sc, eff, loc)
              }
          }
      }
  }

  /**
    * Returns a fresh type environment constructed from the given identifiers `idents`.
    */
  private def typeEnvFromFreeVars(idents: List[Name.Ident])(implicit flix: Flix): Map[String, Type.Var] =
    idents.foldLeft(Map.empty[String, Type.Var]) {
      case (macc, ident) => macc.get(ident.name) match {
        case None =>
          // We use a kind variable since we do not know the kind of the type variable.
          val tvar = Type.freshVar(Kind.freshVar())
          macc + (ident.name -> tvar)
        case Some(tvar) => macc
      }
    }

  /**
    * Performs naming on the given expression `exp0` under the given environments `env0`, `uenv0`, and `tenv0`.
    */
  private def visitExp(exp0: WeededAst.Expression, env0: Map[String, Symbol.VarSym], uenv0: UseEnv, tenv0: Map[String, Type.Var])(implicit flix: Flix): Validation[NamedAst.Expression, NameError] = exp0 match {

    case WeededAst.Expression.Wild(loc) =>
      NamedAst.Expression.Wild(Type.freshVar(Kind.Star), loc).toSuccess

    case WeededAst.Expression.VarOrDefOrSig(qname, loc) if qname.isUnqualified =>
      // the ident name.
      val name = qname.ident.name

      // lookup the name in the var and use environments.
      (env0.get(name), uenv0.defs.get(name)) match {
        case (None, None) =>
          // Case 1: the name is a top-level function.
          NamedAst.Expression.DefOrSig(qname, Type.freshVar(Kind.Star), loc).toSuccess
        case (None, Some(actualQName)) =>
          // Case 2: the name is a use.
          NamedAst.Expression.DefOrSig(actualQName, Type.freshVar(Kind.Star), loc).toSuccess
        case (Some(sym), None) =>
          // Case 3: the name is a variable.
          NamedAst.Expression.Var(sym, loc).toSuccess
        case (Some(sym), Some(qname)) =>
          // Case 4: the name is ambiguous.
          NameError.AmbiguousVarOrUse(name, loc, sym.loc, qname.loc).toFailure
      }

    case WeededAst.Expression.VarOrDefOrSig(name, loc) =>
      NamedAst.Expression.DefOrSig(name, Type.freshVar(Kind.Star), loc).toSuccess

    case WeededAst.Expression.Hole(name, loc) =>
      val tpe = Type.freshVar(Kind.Star)
      val eff = Type.freshVar(Kind.Bool)
      NamedAst.Expression.Hole(name, tpe, eff, loc).toSuccess

    case WeededAst.Expression.Use(uses0, exp, loc) =>
      val uses = uses0.map {
        case WeededAst.Use.UseClass(qname, alias, loc) => NamedAst.Use.UseClass(qname, alias, loc)
        case WeededAst.Use.UseDef(qname, alias, loc) => NamedAst.Use.UseDef(qname, alias, loc)
        case WeededAst.Use.UseTyp(qname, alias, loc) => NamedAst.Use.UseTyp(qname, alias, loc)
        case WeededAst.Use.UseTag(qname, tag, alias, loc) => NamedAst.Use.UseTag(qname, tag, alias, loc)
      }

      flatMapN(mergeUseEnvs(uses0, uenv0)) {
        case uenv1 => mapN(visitExp(exp, env0, uenv1, tenv0)) {
          case e => uses.foldRight(e) {
            case (use, acc) => NamedAst.Expression.Use(use, acc, loc)
          }
        }
      }

    case WeededAst.Expression.Unit(loc) => NamedAst.Expression.Unit(loc).toSuccess

    case WeededAst.Expression.Null(loc) => NamedAst.Expression.Null(loc).toSuccess

    case WeededAst.Expression.True(loc) => NamedAst.Expression.True(loc).toSuccess

    case WeededAst.Expression.False(loc) => NamedAst.Expression.False(loc).toSuccess

    case WeededAst.Expression.Char(lit, loc) => NamedAst.Expression.Char(lit, loc).toSuccess

    case WeededAst.Expression.Float32(lit, loc) => NamedAst.Expression.Float32(lit, loc).toSuccess

    case WeededAst.Expression.Float64(lit, loc) => NamedAst.Expression.Float64(lit, loc).toSuccess

    case WeededAst.Expression.Int8(lit, loc) => NamedAst.Expression.Int8(lit, loc).toSuccess

    case WeededAst.Expression.Int16(lit, loc) => NamedAst.Expression.Int16(lit, loc).toSuccess

    case WeededAst.Expression.Int32(lit, loc) => NamedAst.Expression.Int32(lit, loc).toSuccess

    case WeededAst.Expression.Int64(lit, loc) => NamedAst.Expression.Int64(lit, loc).toSuccess

    case WeededAst.Expression.BigInt(lit, loc) => NamedAst.Expression.BigInt(lit, loc).toSuccess

    case WeededAst.Expression.Str(lit, loc) => NamedAst.Expression.Str(lit, loc).toSuccess

    case WeededAst.Expression.Absent(loc) => NamedAst.Expression.Absent(loc).toSuccess

    case WeededAst.Expression.Present(expOpt, loc) =>
      // TODO: Should not really introduce unit here...
      expOpt match {
        case None => NamedAst.Expression.Present(NamedAst.Expression.Unit(loc), loc).toSuccess
        case Some(exp) => mapN(visitExp(exp, env0, uenv0, tenv0)) {
          case e => NamedAst.Expression.Present(e, loc)
        }
      }

    case WeededAst.Expression.Default(loc) => NamedAst.Expression.Default(loc).toSuccess

    case WeededAst.Expression.Apply(exp, exps, loc) =>
      mapN(visitExp(exp, env0, uenv0, tenv0), traverse(exps)(visitExp(_, env0, uenv0, tenv0))) {
        case (e, es) => NamedAst.Expression.Apply(e, es, loc)
      }

    case WeededAst.Expression.Lambda(fparam0, exp, loc) =>
      flatMapN(visitFormalParam(fparam0, uenv0, tenv0)) {
        case p =>
          val env1 = env0 + (p.sym.text -> p.sym)
          mapN(visitExp(exp, env1, uenv0, tenv0)) {
            case e => NamedAst.Expression.Lambda(p, e, Type.freshVar(Kind.Star), loc)
          }
      }

    case WeededAst.Expression.Unary(op, exp, loc) => visitExp(exp, env0, uenv0, tenv0) map {
      case e => NamedAst.Expression.Unary(op, e, Type.freshVar(Kind.Star), loc)
    }

    case WeededAst.Expression.Binary(op, exp1, exp2, loc) =>
      mapN(visitExp(exp1, env0, uenv0, tenv0), visitExp(exp2, env0, uenv0, tenv0)) {
        case (e1, e2) => NamedAst.Expression.Binary(op, e1, e2, Type.freshVar(Kind.Star), loc)
      }

    case WeededAst.Expression.IfThenElse(exp1, exp2, exp3, loc) =>
      val e1 = visitExp(exp1, env0, uenv0, tenv0)
      val e2 = visitExp(exp2, env0, uenv0, tenv0)
      val e3 = visitExp(exp3, env0, uenv0, tenv0)
      mapN(e1, e2, e3) {
        NamedAst.Expression.IfThenElse(_, _, _, loc)
      }

    case WeededAst.Expression.Stm(exp1, exp2, loc) =>
      val e1 = visitExp(exp1, env0, uenv0, tenv0)
      val e2 = visitExp(exp2, env0, uenv0, tenv0)
      mapN(e1, e2) {
        NamedAst.Expression.Stm(_, _, loc)
      }

    case WeededAst.Expression.Let(ident, exp1, exp2, loc) =>
      // make a fresh variable symbol for the local variable.
      val sym = Symbol.freshVarSym(ident)
      mapN(visitExp(exp1, env0, uenv0, tenv0), visitExp(exp2, env0 + (ident.name -> sym), uenv0, tenv0)) {
        case (e1, e2) => NamedAst.Expression.Let(sym, e1, e2, loc)
      }

    case WeededAst.Expression.Match(exp, rules, loc) =>
      val expVal = visitExp(exp, env0, uenv0, tenv0)
      val rulesVal = traverse(rules) {
        case WeededAst.MatchRule(pat, guard, body) =>
          // extend the environment with every variable occurring in the pattern
          // and perform naming on the rule guard and body under the extended environment.
          val (p, env1) = visitPattern(pat, uenv0)
          val extendedEnv = env0 ++ env1
          mapN(visitExp(guard, extendedEnv, uenv0, tenv0), visitExp(body, extendedEnv, uenv0, tenv0)) {
            case (g, b) => NamedAst.MatchRule(p, g, b)
          }
      }
      mapN(expVal, rulesVal) {
        case (e, rs) => NamedAst.Expression.Match(e, rs, loc)
      }

    case WeededAst.Expression.Choose(exps, rules, loc) =>
      val expsVal = traverse(exps)(visitExp(_, env0, uenv0, tenv0))
      val rulesVal = traverse(rules) {
        case WeededAst.ChoiceRule(pat0, exp0) =>
          val env1 = pat0.foldLeft(Map.empty[String, Symbol.VarSym]) {
            case (acc, WeededAst.ChoicePattern.Wild(loc)) => acc
            case (acc, WeededAst.ChoicePattern.Absent(loc)) => acc
            case (acc, WeededAst.ChoicePattern.Present(ident, loc)) => acc + (ident.name -> Symbol.freshVarSym(ident))
          }
          val p = pat0.map {
            case WeededAst.ChoicePattern.Wild(loc) => NamedAst.ChoicePattern.Wild(loc)
            case WeededAst.ChoicePattern.Absent(loc) => NamedAst.ChoicePattern.Absent(loc)
            case WeededAst.ChoicePattern.Present(ident, loc) => NamedAst.ChoicePattern.Present(env1(ident.name), loc)
          }
          mapN(visitExp(exp0, env0 ++ env1, uenv0, tenv0)) {
            case e => NamedAst.ChoiceRule(p, e)
          }
      }
      mapN(expsVal, rulesVal) {
        case (es, rs) => NamedAst.Expression.Choose(es, rs, loc)
      }

    case WeededAst.Expression.Tag(enumOpt0, tag0, expOpt, loc) =>
      val (enumOpt, tag) = getDisambiguatedTag(enumOpt0, tag0, uenv0)

      expOpt match {
        case None =>
          // Case 1: The tag does not have an expression. Nothing more to be done.
          NamedAst.Expression.Tag(enumOpt, tag, None, Type.freshVar(Kind.Star), loc).toSuccess
        case Some(exp) =>
          // Case 2: The tag has an expression. Perform naming on it.
          visitExp(exp, env0, uenv0, tenv0) map {
            case e => NamedAst.Expression.Tag(enumOpt, tag, Some(e), Type.freshVar(Kind.Star), loc)
          }
      }

    case WeededAst.Expression.Tuple(elms, loc) =>
      traverse(elms)(e => visitExp(e, env0, uenv0, tenv0)) map {
        case es => NamedAst.Expression.Tuple(es, loc)
      }

    case WeededAst.Expression.RecordEmpty(loc) =>
      NamedAst.Expression.RecordEmpty(Type.freshVar(Kind.Record), loc).toSuccess

    case WeededAst.Expression.RecordSelect(exp, label, loc) =>
      mapN(visitExp(exp, env0, uenv0, tenv0)) {
        case e => NamedAst.Expression.RecordSelect(e, label, Type.freshVar(Kind.Star), loc)
      }

    case WeededAst.Expression.RecordExtend(label, value, rest, loc) =>
      mapN(visitExp(value, env0, uenv0, tenv0), visitExp(rest, env0, uenv0, tenv0)) {
        case (v, r) => NamedAst.Expression.RecordExtend(label, v, r, Type.freshVar(Kind.Record), loc)
      }

    case WeededAst.Expression.RecordRestrict(label, rest, loc) =>
      mapN(visitExp(rest, env0, uenv0, tenv0)) {
        case r => NamedAst.Expression.RecordRestrict(label, r, Type.freshVar(Kind.Record), loc)
      }

    case WeededAst.Expression.ArrayLit(elms, loc) =>
      traverse(elms)(e => visitExp(e, env0, uenv0, tenv0)) map {
        case es => NamedAst.Expression.ArrayLit(es, Type.freshVar(Kind.Star), loc)
      }

    case WeededAst.Expression.ArrayNew(elm, len, loc) =>
      mapN(visitExp(elm, env0, uenv0, tenv0), visitExp(len, env0, uenv0, tenv0)) {
        case (es, ln) => NamedAst.Expression.ArrayNew(es, ln, Type.freshVar(Kind.Star), loc)
      }

    case WeededAst.Expression.ArrayLoad(base, index, loc) =>
      mapN(visitExp(base, env0, uenv0, tenv0), visitExp(index, env0, uenv0, tenv0)) {
        case (b, i) => NamedAst.Expression.ArrayLoad(b, i, Type.freshVar(Kind.Star), loc)
      }

    case WeededAst.Expression.ArrayStore(base, index, elm, loc) =>
      mapN(visitExp(base, env0, uenv0, tenv0), visitExp(index, env0, uenv0, tenv0), visitExp(elm, env0, uenv0, tenv0)) {
        case (b, i, e) => NamedAst.Expression.ArrayStore(b, i, e, loc)
      }

    case WeededAst.Expression.ArrayLength(base, loc) =>
      visitExp(base, env0, uenv0, tenv0) map {
        case b => NamedAst.Expression.ArrayLength(b, loc)
      }

    case WeededAst.Expression.ArraySlice(base, startIndex, endIndex, loc) =>
      mapN(visitExp(base, env0, uenv0, tenv0), visitExp(startIndex, env0, uenv0, tenv0), visitExp(endIndex, env0, uenv0, tenv0)) {
        case (b, i1, i2) => NamedAst.Expression.ArraySlice(b, i1, i2, loc)
      }

    case WeededAst.Expression.Ref(exp, loc) =>
      visitExp(exp, env0, uenv0, tenv0) map {
        case e => NamedAst.Expression.Ref(e, loc)
      }

    case WeededAst.Expression.Deref(exp, loc) =>
      visitExp(exp, env0, uenv0, tenv0) map {
        case e => NamedAst.Expression.Deref(e, Type.freshVar(Kind.Star), loc)
      }

    case WeededAst.Expression.Assign(exp1, exp2, loc) =>
      mapN(visitExp(exp1, env0, uenv0, tenv0), visitExp(exp2, env0, uenv0, tenv0)) {
        case (e1, e2) => NamedAst.Expression.Assign(e1, e2, loc)
      }


    case WeededAst.Expression.Existential(tparams0, fparam, exp, loc) =>
      for {
        tparams <- getTypeParamsFromFormalParams(tparams0, List(fparam), WeededAst.Type.Ambiguous(Name.mkQName("Bool"), loc), loc, allowElision = true)
        p <- visitFormalParam(fparam, uenv0, tenv0 ++ getTypeEnv(tparams))
        e <- visitExp(exp, env0 + (p.sym.text -> p.sym), uenv0, tenv0 ++ getTypeEnv(tparams))
      } yield NamedAst.Expression.Existential(p, e, loc) // TODO: Preserve type parameters in NamedAst?

    case WeededAst.Expression.Universal(tparams0, fparam, exp, loc) =>
      for {
        tparams <- getTypeParamsFromFormalParams(tparams0, List(fparam), WeededAst.Type.Ambiguous(Name.mkQName("Bool"), loc), loc, allowElision = true)
        p <- visitFormalParam(fparam, uenv0, tenv0 ++ getTypeEnv(tparams))
        e <- visitExp(exp, env0 + (p.sym.text -> p.sym), uenv0, tenv0 ++ getTypeEnv(tparams))
      } yield NamedAst.Expression.Universal(p, e, loc) // TODO: Preserve type parameters in NamedAst?

    case WeededAst.Expression.Ascribe(exp, expectedType, expectedEff, loc) =>
      val expVal = visitExp(exp, env0, uenv0, tenv0)
      val expectedTypVal = expectedType match {
        case None => (None: Option[NamedAst.Type]).toSuccess
        case Some(t) => mapN(visitType(t, uenv0, tenv0))(x => Some(x))
      }
      val expectedEffVal = expectedEff match {
        case None => (None: Option[NamedAst.Type]).toSuccess
        case Some(f) => mapN(visitType(f, uenv0, tenv0))(x => Some(x))
      }

      mapN(expVal, expectedTypVal, expectedEffVal) {
        case (e, t, f) => NamedAst.Expression.Ascribe(e, t, f, Type.freshVar(Kind.Star), loc)
      }

    case WeededAst.Expression.Cast(exp, declaredType, declaredEff, loc) =>
      val expVal = visitExp(exp, env0, uenv0, tenv0)
      val declaredTypVal = declaredType match {
        case None => (None: Option[NamedAst.Type]).toSuccess
        case Some(t) => mapN(visitType(t, uenv0, tenv0))(x => Some(x))
      }
      val declaredEffVal = declaredEff match {
        case None => (None: Option[NamedAst.Type]).toSuccess
        case Some(f) => mapN(visitType(f, uenv0, tenv0))(x => Some(x))
      }

      mapN(expVal, declaredTypVal, declaredEffVal) {
        case (e, t, f) => NamedAst.Expression.Cast(e, t, f, Type.freshVar(Kind.Star), loc)
      }

    case WeededAst.Expression.TryCatch(exp, rules, loc) =>
      val expVal = visitExp(exp, env0, uenv0, tenv0)
      val rulesVal = traverse(rules) {
        case WeededAst.CatchRule(ident, className, body) =>
          val sym = Symbol.freshVarSym(ident)
          val classVal = lookupClass(className, loc)
          // TODO: Currently the bound name is not available due to bug in code gen.
          // val bodyVal = namer(body, env0 + (ident.name -> sym), tenv0)
          val bodyVal = visitExp(body, env0, uenv0, tenv0)
          mapN(classVal, bodyVal) {
            case (c, b) => NamedAst.CatchRule(sym, c, b)
          }
      }

      mapN(expVal, rulesVal) {
        case (e, rs) => NamedAst.Expression.TryCatch(e, rs, loc)
      }

    case WeededAst.Expression.InvokeConstructor(className, args, sig, loc) =>
      val argsVal = traverse(args)(visitExp(_, env0, uenv0, tenv0))
      val sigVal = traverse(sig)(visitType(_, uenv0, tenv0))
      mapN(argsVal, sigVal) {
        case (as, sig) => NamedAst.Expression.InvokeConstructor(className, as, sig, loc)
      }

    case WeededAst.Expression.InvokeMethod(className, methodName, exp, args, sig, loc) =>
      val expVal = visitExp(exp, env0, uenv0, tenv0)
      val argsVal = traverse(args)(visitExp(_, env0, uenv0, tenv0))
      val sigVal = traverse(sig)(visitType(_, uenv0, tenv0))
      mapN(expVal, argsVal, sigVal) {
        case (e, as, sig) => NamedAst.Expression.InvokeMethod(className, methodName, e, as, sig, loc)
      }

    case WeededAst.Expression.InvokeStaticMethod(className, methodName, args, sig, loc) =>
      val argsVal = traverse(args)(visitExp(_, env0, uenv0, tenv0))
      val sigVal = traverse(sig)(visitType(_, uenv0, tenv0))
      mapN(argsVal, sigVal) {
        case (as, sig) => NamedAst.Expression.InvokeStaticMethod(className, methodName, as, sig, loc)
      }

    case WeededAst.Expression.GetField(className, fieldName, exp, loc) =>
      mapN(visitExp(exp, env0, uenv0, tenv0)) {
        case e => NamedAst.Expression.GetField(className, fieldName, e, loc)
      }

    case WeededAst.Expression.PutField(className, fieldName, exp1, exp2, loc) =>
      mapN(visitExp(exp1, env0, uenv0, tenv0), visitExp(exp2, env0, uenv0, tenv0)) {
        case (e1, e2) => NamedAst.Expression.PutField(className, fieldName, e1, e2, loc)
      }

    case WeededAst.Expression.GetStaticField(className, fieldName, loc) =>
      NamedAst.Expression.GetStaticField(className, fieldName, loc).toSuccess

    case WeededAst.Expression.PutStaticField(className, fieldName, exp, loc) =>
      mapN(visitExp(exp, env0, uenv0, tenv0)) {
        case e => NamedAst.Expression.PutStaticField(className, fieldName, e, loc)
      }

    case WeededAst.Expression.NewChannel(exp, tpe, loc) =>
      mapN(visitExp(exp, env0, uenv0, tenv0), visitType(tpe, uenv0, tenv0)) {
        case (e, t) => NamedAst.Expression.NewChannel(e, t, loc)
      }

    case WeededAst.Expression.GetChannel(exp, loc) =>
      visitExp(exp, env0, uenv0, tenv0) map {
        case e => NamedAst.Expression.GetChannel(e, Type.freshVar(Kind.Star), loc)
      }

    case WeededAst.Expression.PutChannel(exp1, exp2, loc) =>
      mapN(visitExp(exp1, env0, uenv0, tenv0), visitExp(exp2, env0, uenv0, tenv0)) {
        case (e1, e2) => NamedAst.Expression.PutChannel(e1, e2, Type.freshVar(Kind.Star), loc)
      }

    case WeededAst.Expression.SelectChannel(rules, default, loc) =>
      val rulesVal = traverse(rules) {
        case WeededAst.SelectChannelRule(ident, chan, body) =>
          // make a fresh variable symbol for the local recursive variable.
          val sym = Symbol.freshVarSym(ident)
          val env1 = env0 + (ident.name -> sym)
          mapN(visitExp(chan, env0, uenv0, tenv0), visitExp(body, env1, uenv0, tenv0)) {
            case (c, b) => NamedAst.SelectChannelRule(sym, c, b)
          }
      }

      val defaultVal = default match {
        case Some(exp) => visitExp(exp, env0, uenv0, tenv0) map {
          case e => Some(e)
        }
        case None => None.toSuccess
      }

      mapN(rulesVal, defaultVal) {
        case (rs, d) => NamedAst.Expression.SelectChannel(rs, d, Type.freshVar(Kind.Star), loc)
      }

    case WeededAst.Expression.Spawn(exp, loc) =>
      visitExp(exp, env0, uenv0, tenv0) map {
        case e => NamedAst.Expression.Spawn(e, loc)
      }

    case WeededAst.Expression.Lazy(exp, loc) =>
      visitExp(exp, env0, uenv0, tenv0) map {
        case e => NamedAst.Expression.Lazy(e, loc)
      }

    case WeededAst.Expression.Force(exp, loc) =>
      visitExp(exp, env0, uenv0, tenv0) map {
        case e => NamedAst.Expression.Force(e, Type.freshVar(Kind.Star), loc)
      }

    case WeededAst.Expression.FixpointConstraintSet(cs0, loc) =>
      mapN(traverse(cs0)(visitConstraint(_, env0, uenv0, tenv0))) {
        case cs =>
          NamedAst.Expression.FixpointConstraintSet(cs, Type.freshVar(Kind.Schema), loc)
      }

    case WeededAst.Expression.FixpointCompose(exp1, exp2, loc) =>
      mapN(visitExp(exp1, env0, uenv0, tenv0), visitExp(exp2, env0, uenv0, tenv0)) {
        case (e1, e2) => NamedAst.Expression.FixpointCompose(e1, e2, loc)
      }

    case WeededAst.Expression.FixpointSolve(exp, loc) =>
      visitExp(exp, env0, uenv0, tenv0) map {
        case e => NamedAst.Expression.FixpointSolve(e, loc)
      }

    case WeededAst.Expression.FixpointProject(ident, exp, loc) =>
      mapN(visitExp(exp, env0, uenv0, tenv0)) {
        case e => NamedAst.Expression.FixpointProject(ident, e, Type.freshVar(Kind.Schema), loc)
      }

    case WeededAst.Expression.FixpointEntails(exp1, exp2, loc) =>
      mapN(visitExp(exp1, env0, uenv0, tenv0), visitExp(exp2, env0, uenv0, tenv0)) {
        case (e1, e2) => NamedAst.Expression.FixpointEntails(e1, e2, loc)
      }

    case WeededAst.Expression.FixpointFold(ident, init, f, constraints, loc) =>
      mapN(visitExp(init, env0, uenv0, tenv0), visitExp(f, env0, uenv0, tenv0), visitExp(constraints, env0, uenv0, tenv0)) {
        case (e1, e2, e3) => NamedAst.Expression.FixpointFold(ident, e1, e2, e3, Type.freshVar(Kind.Star), loc)
      }
  }

  /**
    * Names the given pattern `pat0` and returns map from variable names to variable symbols.
    */
  private def visitPattern(pat0: WeededAst.Pattern, uenv0: UseEnv)(implicit flix: Flix): (NamedAst.Pattern, Map[String, Symbol.VarSym]) = {
    val m = mutable.Map.empty[String, Symbol.VarSym]

    def visit(p: WeededAst.Pattern): NamedAst.Pattern = p match {
      case WeededAst.Pattern.Wild(loc) => NamedAst.Pattern.Wild(Type.freshVar(Kind.Star), loc)
      case WeededAst.Pattern.Var(ident, loc) =>
        // make a fresh variable symbol for the local variable.
        val sym = Symbol.freshVarSym(ident)
        m += (ident.name -> sym)
        NamedAst.Pattern.Var(sym, Type.freshVar(Kind.Star), loc)
      case WeededAst.Pattern.Unit(loc) => NamedAst.Pattern.Unit(loc)
      case WeededAst.Pattern.True(loc) => NamedAst.Pattern.True(loc)
      case WeededAst.Pattern.False(loc) => NamedAst.Pattern.False(loc)
      case WeededAst.Pattern.Char(lit, loc) => NamedAst.Pattern.Char(lit, loc)
      case WeededAst.Pattern.Float32(lit, loc) => NamedAst.Pattern.Float32(lit, loc)
      case WeededAst.Pattern.Float64(lit, loc) => NamedAst.Pattern.Float64(lit, loc)
      case WeededAst.Pattern.Int8(lit, loc) => NamedAst.Pattern.Int8(lit, loc)
      case WeededAst.Pattern.Int16(lit, loc) => NamedAst.Pattern.Int16(lit, loc)
      case WeededAst.Pattern.Int32(lit, loc) => NamedAst.Pattern.Int32(lit, loc)
      case WeededAst.Pattern.Int64(lit, loc) => NamedAst.Pattern.Int64(lit, loc)
      case WeededAst.Pattern.BigInt(lit, loc) => NamedAst.Pattern.BigInt(lit, loc)
      case WeededAst.Pattern.Str(lit, loc) => NamedAst.Pattern.Str(lit, loc)

      case WeededAst.Pattern.Tag(enumOpt0, tag0, pat, loc) =>
        val (enumOpt, tag) = getDisambiguatedTag(enumOpt0, tag0, uenv0)
        NamedAst.Pattern.Tag(enumOpt, tag, visit(pat), Type.freshVar(Kind.Star), loc)

      case WeededAst.Pattern.Tuple(elms, loc) => NamedAst.Pattern.Tuple(elms map visit, loc)

      case WeededAst.Pattern.Array(elms, loc) => NamedAst.Pattern.Array(elms map visit, Type.freshVar(Kind.Star), loc)

      case WeededAst.Pattern.ArrayTailSpread(elms, ident, loc) => ident match {
        case None =>
          val sym = Symbol.freshVarSym("_")
          NamedAst.Pattern.ArrayTailSpread(elms map visit, sym, Type.freshVar(Kind.Star), loc)
        case Some(id) =>
          val sym = Symbol.freshVarSym(id)
          m += (id.name -> sym)
          NamedAst.Pattern.ArrayTailSpread(elms map visit, sym, Type.freshVar(Kind.Star), loc)
      }
      case WeededAst.Pattern.ArrayHeadSpread(ident, elms, loc) => ident match {
        case None =>
          val sym = Symbol.freshVarSym("_")
          NamedAst.Pattern.ArrayTailSpread(elms map visit, sym, Type.freshVar(Kind.Star), loc)
        case Some(id) =>
          val sym = Symbol.freshVarSym(id)
          m += (id.name -> sym)
          NamedAst.Pattern.ArrayHeadSpread(sym, elms map visit, Type.freshVar(Kind.Star), loc)
      }
    }

    (visit(pat0), m.toMap)
  }

  /**
    * Names the given pattern `pat0` under the given environments `env0` and `uenv0`.
    *
    * Every variable in the pattern must be bound by the environment.
    */
  private def visitPattern(pat0: WeededAst.Pattern, env0: Map[String, Symbol.VarSym], uenv0: UseEnv)(implicit flix: Flix): NamedAst.Pattern = {
    def visit(p: WeededAst.Pattern): NamedAst.Pattern = p match {
      case WeededAst.Pattern.Wild(loc) => NamedAst.Pattern.Wild(Type.freshVar(Kind.Star), loc)
      case WeededAst.Pattern.Var(ident, loc) =>
        val sym = env0(ident.name)
        NamedAst.Pattern.Var(sym, sym.tvar, loc)
      case WeededAst.Pattern.Unit(loc) => NamedAst.Pattern.Unit(loc)
      case WeededAst.Pattern.True(loc) => NamedAst.Pattern.True(loc)
      case WeededAst.Pattern.False(loc) => NamedAst.Pattern.False(loc)
      case WeededAst.Pattern.Char(lit, loc) => NamedAst.Pattern.Char(lit, loc)
      case WeededAst.Pattern.Float32(lit, loc) => NamedAst.Pattern.Float32(lit, loc)
      case WeededAst.Pattern.Float64(lit, loc) => NamedAst.Pattern.Float64(lit, loc)
      case WeededAst.Pattern.Int8(lit, loc) => NamedAst.Pattern.Int8(lit, loc)
      case WeededAst.Pattern.Int16(lit, loc) => NamedAst.Pattern.Int16(lit, loc)
      case WeededAst.Pattern.Int32(lit, loc) => NamedAst.Pattern.Int32(lit, loc)
      case WeededAst.Pattern.Int64(lit, loc) => NamedAst.Pattern.Int64(lit, loc)
      case WeededAst.Pattern.BigInt(lit, loc) => NamedAst.Pattern.BigInt(lit, loc)
      case WeededAst.Pattern.Str(lit, loc) => NamedAst.Pattern.Str(lit, loc)

      case WeededAst.Pattern.Tag(enumOpt0, tag0, pat, loc) =>
        val (enumOpt, tag) = getDisambiguatedTag(enumOpt0, tag0, uenv0)
        NamedAst.Pattern.Tag(enumOpt, tag, visit(pat), Type.freshVar(Kind.Star), loc)

      case WeededAst.Pattern.Tuple(elms, loc) => NamedAst.Pattern.Tuple(elms map visit, loc)

      case WeededAst.Pattern.Array(elms, loc) => NamedAst.Pattern.Array(elms map visit, Type.freshVar(Kind.Star), loc)
      case WeededAst.Pattern.ArrayTailSpread(elms, ident, loc) => ident match {
        case None => NamedAst.Pattern.ArrayTailSpread(elms map visit, Symbol.freshVarSym("_"), Type.freshVar(Kind.Star), loc)
        case Some(value) =>
          val sym = env0(value.name)
          NamedAst.Pattern.ArrayTailSpread(elms map visit, sym, Type.freshVar(Kind.Star), loc)
      }
      case WeededAst.Pattern.ArrayHeadSpread(ident, elms, loc) => ident match {
        case None => NamedAst.Pattern.ArrayHeadSpread(Symbol.freshVarSym("_"), elms map visit, Type.freshVar(Kind.Star), loc)
        case Some(value) =>
          val sym = env0(value.name)
          NamedAst.Pattern.ArrayHeadSpread(sym, elms map visit, Type.freshVar(Kind.Star), loc)
      }
    }

    visit(pat0)
  }

  /**
    * Names the given head predicate `head` under the given environments `env0`, `uenv0`, and `tenv0`.
    */
  private def visitHeadPredicate(head: WeededAst.Predicate.Head, outerEnv: Map[String, Symbol.VarSym], headEnv0: Map[String, Symbol.VarSym], ruleEnv0: Map[String, Symbol.VarSym], uenv0: UseEnv, tenv0: Map[String, Type.Var])(implicit flix: Flix): Validation[NamedAst.Predicate.Head, NameError] = head match {
    case WeededAst.Predicate.Head.Atom(ident, den, terms, loc) =>
      for {
        ts <- traverse(terms)(t => visitExp(t, outerEnv ++ headEnv0 ++ ruleEnv0, uenv0, tenv0))
      } yield NamedAst.Predicate.Head.Atom(ident, den, ts, Type.freshVar(Kind.Star), loc)

    case WeededAst.Predicate.Head.Union(exp, loc) =>
      for {
        e <- visitExp(exp, outerEnv ++ headEnv0 ++ ruleEnv0, uenv0, tenv0)
      } yield NamedAst.Predicate.Head.Union(e, Type.freshVar(Kind.Star), loc)
  }

  /**
    * Names the given body predicate `body` under the given environments `env0`, `uenv0`, and `tenv0`.
    */
  private def visitBodyPredicate(body: WeededAst.Predicate.Body, outerEnv: Map[String, Symbol.VarSym], headEnv0: Map[String, Symbol.VarSym], ruleEnv0: Map[String, Symbol.VarSym], uenv0: UseEnv, tenv0: Map[String, Type.Var])(implicit flix: Flix): Validation[NamedAst.Predicate.Body, NameError] = body match {
    case WeededAst.Predicate.Body.Atom(ident, den, polarity, terms, loc) =>
      val ts = terms.map(t => visitPattern(t, outerEnv ++ ruleEnv0, uenv0))
      NamedAst.Predicate.Body.Atom(ident, den, polarity, ts, Type.freshVar(Kind.Star), loc).toSuccess

    case WeededAst.Predicate.Body.Guard(exp, loc) =>
      for {
        e <- visitExp(exp, outerEnv ++ headEnv0 ++ ruleEnv0, uenv0, tenv0)
      } yield NamedAst.Predicate.Body.Guard(e, loc)
  }

  /**
    * Returns the identifiers that are visible in the head scope by the given body predicate `p0`.
    */
  private def visibleInHeadScope(p0: WeededAst.Predicate.Body): List[Name.Ident] = p0 match {
    case WeededAst.Predicate.Body.Atom(qname, den, polarity, terms, loc) => terms.flatMap(freeVars)
    case WeededAst.Predicate.Body.Guard(exp, loc) => Nil
  }

  /**
    * Returns the identifiers that are visible in the rule scope by the given body predicate `p0`.
    */
  private def visibleInRuleScope(p0: WeededAst.Predicate.Body): List[Name.Ident] = p0 match {
    case WeededAst.Predicate.Body.Atom(qname, den, polarity, terms, loc) => terms.flatMap(freeVars)
    case WeededAst.Predicate.Body.Guard(exp, loc) => Nil
  }

  /**
    * Names the given type `tpe` under the given environments `uenv0` and `tenv0`.
    */
  private def visitType(tpe0: WeededAst.Type, uenv0: UseEnv, tenv0: Map[String, Type.Var])(implicit flix: Flix): Validation[NamedAst.Type, NameError] = tpe0 match {
    case WeededAst.Type.Unit(loc) => NamedAst.Type.Unit(loc).toSuccess

    case WeededAst.Type.Var(ident, loc) =>
      //
      // Check for [[NameError.SuspiciousTypeVarName]].
      //
      if (isSuspiciousTypeVarName(ident.name)) {
        NameError.SuspiciousTypeVarName(ident.name, loc).toFailure
      } else if (ident.isWild) {
        // Wild idents will not be in the environment. Create a tvar instead.
        NamedAst.Type.Var(Type.freshVar(Kind.freshVar()), loc).toSuccess
      } else {
          tenv0.get(ident.name) match {
            case None => NameError.UndefinedTypeVar(ident.name, loc).toFailure
            case Some(tvar) => NamedAst.Type.Var(tvar, loc).toSuccess
          }
      }

    case WeededAst.Type.Ambiguous(qname, loc) =>
      if (qname.isUnqualified) {
        val name = qname.ident.name
        // Disambiguate the qname.
        (tenv0.get(name), uenv0.tpes.get(name)) match {
          case (None, None) =>
            // Case 1: the name is top-level type.
            NamedAst.Type.Ambiguous(qname, loc).toSuccess

          case (Some(tvar), None) =>
            // Case 2: the name is a type variable.
            NamedAst.Type.Var(tvar, loc).toSuccess

          case (None, Some(actualQName)) =>
            // Case 3: the name is a use.
            NamedAst.Type.Ambiguous(actualQName, loc).toSuccess

          case (Some(tvar), Some(qname)) =>
            // Case 4: the name is ambiguous.
            throw InternalCompilerException(s"Unexpected ambiguous type.")
        }
      }
      else
        NamedAst.Type.Ambiguous(qname, loc).toSuccess

    case WeededAst.Type.Tuple(elms, loc) =>
      mapN(traverse(elms)(visitType(_, uenv0, tenv0))) {
        case ts => NamedAst.Type.Tuple(ts, loc)
      }

    case WeededAst.Type.RecordEmpty(loc) =>
      NamedAst.Type.RecordEmpty(loc).toSuccess

    case WeededAst.Type.RecordExtend(label, value, rest, loc) =>
      mapN(visitType(value, uenv0, tenv0), visitType(rest, uenv0, tenv0)) {
        case (t, r) => NamedAst.Type.RecordExtend(label, t, r, loc)
      }

    case WeededAst.Type.RecordGeneric(tvar, loc) =>
      visitType(tvar, uenv0, tenv0)

    case WeededAst.Type.SchemaEmpty(loc) =>
      NamedAst.Type.SchemaEmpty(loc).toSuccess

    case WeededAst.Type.SchemaExtendByAlias(qname, targs, rest, loc) =>
      // Disambiguate the qname.
      val name = if (qname.isUnqualified) {
        uenv0.tpes.getOrElse(qname.ident.name, qname)
      } else {
        qname
      }

      mapN(traverse(targs)(visitType(_, uenv0, tenv0)), visitType(rest, uenv0, tenv0)) {
        case (ts, r) => NamedAst.Type.SchemaExtendWithAlias(name, ts, r, loc)
      }

    case WeededAst.Type.SchemaExtendByTypes(ident, den, tpes, rest, loc) =>
      mapN(traverse(tpes)(visitType(_, uenv0, tenv0)), visitType(rest, uenv0, tenv0)) {
        case (ts, r) => NamedAst.Type.SchemaExtendWithTypes(ident, den, ts, r, loc)
      }

    case WeededAst.Type.SchemaGeneric(tvar, loc) =>
      visitType(tvar, uenv0, tenv0)

    case WeededAst.Type.Relation(tpes, loc) =>
      mapN(traverse(tpes)(visitType(_, uenv0, tenv0))) {
        case ts => NamedAst.Type.Relation(ts, loc)
      }

    case WeededAst.Type.Lattice(tpes, loc) =>
      mapN(traverse(tpes)(visitType(_, uenv0, tenv0))) {
        case ts => NamedAst.Type.Lattice(ts, loc)
      }

    case WeededAst.Type.Native(fqn, loc) =>
      NamedAst.Type.Native(fqn, loc).toSuccess

    case WeededAst.Type.Arrow(tparams, eff, tresult, loc) =>
      mapN(traverse(tparams)(visitType(_, uenv0, tenv0)), visitType(eff, uenv0, tenv0), visitType(tresult, uenv0, tenv0)) {
        case (ts, f, t) => NamedAst.Type.Arrow(ts, f, t, loc)
      }

    case WeededAst.Type.Apply(tpe1, tpe2, loc) =>
      mapN(visitType(tpe1, uenv0, tenv0), visitType(tpe2, uenv0, tenv0)) {
        case (t1, t2) => NamedAst.Type.Apply(t1, t2, loc)
      }

    case WeededAst.Type.True(loc) =>
      NamedAst.Type.True(loc).toSuccess

    case WeededAst.Type.False(loc) =>
      NamedAst.Type.False(loc).toSuccess

    case WeededAst.Type.Not(tpe, loc) =>
      mapN(visitType(tpe, uenv0, tenv0)) {
        case t => NamedAst.Type.Not(t, loc)
      }

    case WeededAst.Type.And(tpe1, tpe2, loc) =>
      mapN(visitType(tpe1, uenv0, tenv0), visitType(tpe2, uenv0, tenv0)) {
        case (t1, t2) => NamedAst.Type.And(t1, t2, loc)
      }

    case WeededAst.Type.Or(tpe1, tpe2, loc) =>
      mapN(visitType(tpe1, uenv0, tenv0), visitType(tpe2, uenv0, tenv0)) {
        case (t1, t2) => NamedAst.Type.Or(t1, t2, loc)
      }

  }

  /**
    * Returns `true` if the given string `s` is a suspicious type variable name.
    */
  private def isSuspiciousTypeVarName(s: String): Boolean = s match {
    case "unit" => true
    case "bool" => true
    case "char" => true
    case "float" => true
    case "float32" => true
    case "float64" => true
    case "int" => true
    case "int8" => true
    case "int16" => true
    case "int32" => true
    case "int64" => true
    case "bigint" => true
    case "str" => true
    case "string" => true
    case "array" => true
    case "ref" => true
    case "pure" => true
    case "impure" => true
    case _ => false
  }

  /**
    * Returns all the free variables in the given expression `exp0`.
    */
  private def freeVars(exp0: WeededAst.Expression): List[Name.Ident] = exp0 match {
    case WeededAst.Expression.Wild(loc) => Nil
    case WeededAst.Expression.VarOrDefOrSig(qname, loc) => List(qname.ident)
    case WeededAst.Expression.Hole(name, loc) => Nil
    case WeededAst.Expression.Use(_, exp, _) => freeVars(exp)
    case WeededAst.Expression.Unit(loc) => Nil
    case WeededAst.Expression.Null(loc) => Nil
    case WeededAst.Expression.True(loc) => Nil
    case WeededAst.Expression.False(loc) => Nil
    case WeededAst.Expression.Char(lit, loc) => Nil
    case WeededAst.Expression.Float32(lit, loc) => Nil
    case WeededAst.Expression.Float64(lit, loc) => Nil
    case WeededAst.Expression.Int8(lit, loc) => Nil
    case WeededAst.Expression.Int16(lit, loc) => Nil
    case WeededAst.Expression.Int32(lit, loc) => Nil
    case WeededAst.Expression.Int64(lit, loc) => Nil
    case WeededAst.Expression.BigInt(lit, loc) => Nil
    case WeededAst.Expression.Str(lit, loc) => Nil
    case WeededAst.Expression.Absent(loc) => Nil
    case WeededAst.Expression.Present(expOpt, loc) => expOpt.map(freeVars).getOrElse(Nil)
    case WeededAst.Expression.Default(loc) => Nil
    case WeededAst.Expression.Apply(exp, exps, loc) => freeVars(exp) ++ exps.flatMap(freeVars)
    case WeededAst.Expression.Lambda(fparam, exp, loc) => filterBoundVars(freeVars(exp), List(fparam.ident))
    case WeededAst.Expression.Unary(op, exp, loc) => freeVars(exp)
    case WeededAst.Expression.Binary(op, exp1, exp2, loc) => freeVars(exp1) ++ freeVars(exp2)
    case WeededAst.Expression.IfThenElse(exp1, exp2, exp3, loc) => freeVars(exp1) ++ freeVars(exp2) ++ freeVars(exp3)
    case WeededAst.Expression.Stm(exp1, exp2, loc) => freeVars(exp1) ++ freeVars(exp2)
    case WeededAst.Expression.Let(ident, exp1, exp2, loc) => freeVars(exp1) ++ filterBoundVars(freeVars(exp2), List(ident))
    case WeededAst.Expression.Match(exp, rules, loc) => freeVars(exp) ++ rules.flatMap {
      case WeededAst.MatchRule(pat, guard, body) => filterBoundVars(freeVars(guard) ++ freeVars(body), freeVars(pat))
    }
    case WeededAst.Expression.Choose(exps, rules, loc) => exps.flatMap(freeVars) ++ rules.flatMap {
      case WeededAst.ChoiceRule(pat, exp) => filterBoundVars(freeVars(exp), pat.flatMap(freeVars))
    }
    case WeededAst.Expression.Tag(enum, tag, expOpt, loc) => expOpt.map(freeVars).getOrElse(Nil)
    case WeededAst.Expression.Tuple(elms, loc) => elms.flatMap(freeVars)
    case WeededAst.Expression.RecordEmpty(loc) => Nil
    case WeededAst.Expression.RecordSelect(exp, label, loc) => freeVars(exp)
    case WeededAst.Expression.RecordExtend(label, exp, rest, loc) => freeVars(exp) ++ freeVars(rest)
    case WeededAst.Expression.RecordRestrict(label, rest, loc) => freeVars(rest)
    case WeededAst.Expression.ArrayLit(elms, loc) => elms.flatMap(freeVars)
    case WeededAst.Expression.ArrayNew(elm, len, loc) => freeVars(elm) ++ freeVars(len)
    case WeededAst.Expression.ArrayLoad(base, index, loc) => freeVars(base) ++ freeVars(index)
    case WeededAst.Expression.ArrayStore(base, index, elm, loc) => freeVars(base) ++ freeVars(index) ++ freeVars(elm)
    case WeededAst.Expression.ArrayLength(base, loc) => freeVars(base)
    case WeededAst.Expression.ArraySlice(base, startIndex, endIndex, loc) => freeVars(base) ++ freeVars(startIndex) ++ freeVars(endIndex)
    case WeededAst.Expression.Ref(exp, loc) => freeVars(exp)
    case WeededAst.Expression.Deref(exp, loc) => freeVars(exp)
    case WeededAst.Expression.Assign(exp1, exp2, loc) => freeVars(exp1) ++ freeVars(exp2)
    case WeededAst.Expression.Existential(tparams, fparam, exp, loc) => filterBoundVars(freeVars(exp), List(fparam.ident))
    case WeededAst.Expression.Universal(tparams, fparam, exp, loc) => filterBoundVars(freeVars(exp), List(fparam.ident))
    case WeededAst.Expression.Ascribe(exp, tpe, eff, loc) => freeVars(exp)
    case WeededAst.Expression.Cast(exp, tpe, eff, loc) => freeVars(exp)
    case WeededAst.Expression.TryCatch(exp, rules, loc) =>
      rules.foldLeft(freeVars(exp)) {
        case (fvs, WeededAst.CatchRule(ident, className, body)) => filterBoundVars(freeVars(body), List(ident))
      }
    case WeededAst.Expression.InvokeConstructor(className, args, sig, loc) => args.flatMap(freeVars)
    case WeededAst.Expression.InvokeMethod(className, methodName, exp, args, sig, loc) => freeVars(exp) ++ args.flatMap(freeVars)
    case WeededAst.Expression.InvokeStaticMethod(className, methodName, args, sig, loc) => args.flatMap(freeVars)
    case WeededAst.Expression.GetField(className, fieldName, exp, loc) => freeVars(exp)
    case WeededAst.Expression.PutField(className, fieldName, exp1, exp2, loc) => freeVars(exp1) ++ freeVars(exp2)
    case WeededAst.Expression.GetStaticField(className, fieldName, loc) => Nil
    case WeededAst.Expression.PutStaticField(className, fieldName, exp, loc) => freeVars(exp)
    case WeededAst.Expression.NewChannel(tpe, exp, loc) => freeVars(exp)
    case WeededAst.Expression.GetChannel(exp, loc) => freeVars(exp)
    case WeededAst.Expression.PutChannel(exp1, exp2, loc) => freeVars(exp1) ++ freeVars(exp2)
    case WeededAst.Expression.SelectChannel(rules, default, loc) =>
      val rulesFreeVars = rules.flatMap {
        case WeededAst.SelectChannelRule(ident, chan, exp) =>
          freeVars(chan) ++ filterBoundVars(freeVars(exp), List(ident))
      }
      val defaultFreeVars = default.map(freeVars).getOrElse(Nil)
      rulesFreeVars ++ defaultFreeVars
    case WeededAst.Expression.Spawn(exp, loc) => freeVars(exp)
    case WeededAst.Expression.Lazy(exp, loc) => freeVars(exp)
    case WeededAst.Expression.Force(exp, loc) => freeVars(exp)
    case WeededAst.Expression.FixpointConstraintSet(cs, loc) => cs.flatMap(freeVarsConstraint)
    case WeededAst.Expression.FixpointCompose(exp1, exp2, loc) => freeVars(exp1) ++ freeVars(exp2)
    case WeededAst.Expression.FixpointSolve(exp, loc) => freeVars(exp)
    case WeededAst.Expression.FixpointProject(qname, exp, loc) => freeVars(exp)
    case WeededAst.Expression.FixpointEntails(exp1, exp2, loc) => freeVars(exp1) ++ freeVars(exp2)
    case WeededAst.Expression.FixpointFold(qname, exp1, exp2, exp3, loc) => freeVars(exp1) ++ freeVars(exp2) ++ freeVars(exp3)
  }

  /**
    * Returns all the free variables in the given pattern `pat0`.
    */
  private def freeVars(pat0: WeededAst.Pattern): List[Name.Ident] = pat0 match {
    case WeededAst.Pattern.Var(ident, loc) => List(ident)
    case WeededAst.Pattern.Wild(loc) => Nil
    case WeededAst.Pattern.Unit(loc) => Nil
    case WeededAst.Pattern.True(loc) => Nil
    case WeededAst.Pattern.False(loc) => Nil
    case WeededAst.Pattern.Char(lit, loc) => Nil
    case WeededAst.Pattern.Float32(lit, loc) => Nil
    case WeededAst.Pattern.Float64(lit, loc) => Nil
    case WeededAst.Pattern.Int8(lit, loc) => Nil
    case WeededAst.Pattern.Int16(lit, loc) => Nil
    case WeededAst.Pattern.Int32(lit, loc) => Nil
    case WeededAst.Pattern.Int64(lit, loc) => Nil
    case WeededAst.Pattern.BigInt(lit, loc) => Nil
    case WeededAst.Pattern.Str(lit, loc) => Nil
    case WeededAst.Pattern.Tag(enumName, tagName, p, loc) => freeVars(p)
    case WeededAst.Pattern.Tuple(elms, loc) => elms flatMap freeVars
    case WeededAst.Pattern.Array(elms, loc) => elms flatMap freeVars
    case WeededAst.Pattern.ArrayTailSpread(elms, ident, loc) =>
      val freeElms = elms flatMap freeVars
      ident match {
        case None => freeElms
        case Some(value) => freeElms.appended(value)
      }
    case WeededAst.Pattern.ArrayHeadSpread(ident, elms, loc) =>
      val freeElms = elms flatMap freeVars
      ident match {
        case None => freeElms
        case Some(value) => freeElms.appended(value)
      }
  }

  /**
    * Returns all free variables in the given null pattern `pat0`.
    */
  private def freeVars(pat0: WeededAst.ChoicePattern): List[Name.Ident] = pat0 match {
    case ChoicePattern.Wild(_) => Nil
    case ChoicePattern.Present(ident, _) => ident :: Nil
    case ChoicePattern.Absent(_) => Nil
  }

  /**
    * Returns the free variables in the given type `tpe0`.
    */
  private def freeVars(tpe0: WeededAst.Type): List[Name.Ident] = tpe0 match {
    case WeededAst.Type.Var(ident, loc) => ident :: Nil
    case WeededAst.Type.Ambiguous(qname, loc) => Nil
    case WeededAst.Type.Unit(loc) => Nil
    case WeededAst.Type.Tuple(elms, loc) => elms.flatMap(freeVars)
    case WeededAst.Type.RecordEmpty(loc) => Nil
    case WeededAst.Type.RecordExtend(l, t, r, loc) => freeVars(t) ::: freeVars(r)
    case WeededAst.Type.RecordGeneric(t, loc) => freeVars(t)
    case WeededAst.Type.SchemaEmpty(loc) => Nil
    case WeededAst.Type.SchemaExtendByTypes(_, _, ts, r, loc) => ts.flatMap(freeVars) ::: freeVars(r)
    case WeededAst.Type.SchemaExtendByAlias(_, ts, r, _) => ts.flatMap(freeVars) ::: freeVars(r)
    case WeededAst.Type.SchemaGeneric(t, loc) => freeVars(t)
    case WeededAst.Type.Relation(ts, loc) => ts.flatMap(freeVars)
    case WeededAst.Type.Lattice(ts, loc) => ts.flatMap(freeVars)
    case WeededAst.Type.Native(fqm, loc) => Nil
    case WeededAst.Type.Arrow(tparams, eff, tresult, loc) => tparams.flatMap(freeVars) ::: freeVars(eff) ::: freeVars(tresult)
    case WeededAst.Type.Apply(tpe1, tpe2, loc) => freeVars(tpe1) ++ freeVars(tpe2)
    case WeededAst.Type.True(loc) => Nil
    case WeededAst.Type.False(loc) => Nil
    case WeededAst.Type.Not(tpe, loc) => freeVars(tpe)
    case WeededAst.Type.And(tpe1, tpe2, loc) => freeVars(tpe1) ++ freeVars(tpe2)
    case WeededAst.Type.Or(tpe1, tpe2, loc) => freeVars(tpe1) ++ freeVars(tpe2)
  }

  /**
    * Returns the free vars and their inferred kinds in the given type `tpe0`, with `varKind` assigned if `tpe0` is a type var.
    */
  private def freeVarsWithKind(tpe0: WeededAst.Type): List[(Name.Ident, Kind)] = {
    def visit(tpe0: WeededAst.Type, varKind: Kind): List[(Name.Ident, Kind)] = tpe0 match {
      case WeededAst.Type.Var(ident, loc) => List(ident -> varKind)
      case WeededAst.Type.Ambiguous(qname, loc) => Nil
      case WeededAst.Type.Unit(loc) => Nil
      case WeededAst.Type.Tuple(elms, loc) => elms.flatMap(visit(_, Kind.Star))
      case WeededAst.Type.RecordEmpty(loc) => Nil
      case WeededAst.Type.RecordExtend(l, t, r, loc) => visit(t, Kind.Star) ::: visit(r, Kind.Record)
      case WeededAst.Type.RecordGeneric(t, loc) => visit(t, Kind.Record)
      case WeededAst.Type.SchemaEmpty(loc) => Nil
      case WeededAst.Type.SchemaExtendByTypes(_, _, ts, r, loc) => ts.flatMap(visit(_, Kind.Star)) ::: visit(r, Kind.Schema)
      case WeededAst.Type.SchemaExtendByAlias(_, ts, r, _) => ts.flatMap(visit(_, Kind.Star)) ::: visit(r, Kind.Schema)
      case WeededAst.Type.SchemaGeneric(t, loc) => visit(t, Kind.Schema)
      case WeededAst.Type.Relation(ts, loc) => ts.flatMap(visit(_, Kind.Star))
      case WeededAst.Type.Lattice(ts, loc) => ts.flatMap(visit(_, Kind.Star))
      case WeededAst.Type.Native(fqm, loc) => Nil
      case WeededAst.Type.Arrow(tparams, eff, tresult, loc) => tparams.flatMap(visit(_, Kind.Star)) ::: visit(eff, Kind.Bool) ::: visit(tresult, Kind.Star)
      case WeededAst.Type.Apply(tpe1, tpe2, loc) => visit(tpe1, Kind.Star) ++ visit(tpe2, Kind.Star)
      case WeededAst.Type.True(loc) => Nil
      case WeededAst.Type.False(loc) => Nil
      case WeededAst.Type.Not(tpe, loc) => visit(tpe, Kind.Bool)
      case WeededAst.Type.And(tpe1, tpe2, loc) => visit(tpe1, Kind.Bool) ++ visit(tpe2, Kind.Bool)
      case WeededAst.Type.Or(tpe1, tpe2, loc) => visit(tpe1, Kind.Bool) ++ visit(tpe2, Kind.Bool)
    }

    visit(tpe0, Kind.Star)
  }

  /**
    * Returns the free variables in the given constraint `c0`.
    */
  private def freeVarsConstraint(c0: WeededAst.Constraint): List[Name.Ident] = c0 match {
    case WeededAst.Constraint(head, body, loc) => freeVarsHeadPred(head) ::: body.flatMap(freeVarsBodyPred)
  }

  /**
    * Returns the free variables in the given head predicate `h0`.
    */
  private def freeVarsHeadPred(h0: WeededAst.Predicate.Head): List[Name.Ident] = h0 match {
    case WeededAst.Predicate.Head.Atom(qname, den, terms, loc) => terms.flatMap(freeVars)
    case WeededAst.Predicate.Head.Union(exp, loc) => freeVars(exp)
  }

  /**
    * Returns the free variables in the given body predicate `b0`.
    */
  private def freeVarsBodyPred(b0: WeededAst.Predicate.Body): List[Name.Ident] = b0 match {
    case WeededAst.Predicate.Body.Atom(qname, den, polarity, terms, loc) => terms.flatMap(freeVars)
    case WeededAst.Predicate.Body.Guard(exp, loc) => freeVars(exp)
  }

  /**
    * Translates the given weeded annotation to a named annotation.
    */
  private def visitAnnotation(ann: WeededAst.Annotation, env0: Map[String, Symbol.VarSym], uenv0: UseEnv, tenv0: Map[String, Type.Var])(implicit flix: Flix): Validation[NamedAst.Annotation, NameError] = ann match {
    case WeededAst.Annotation(name, args, loc) =>
      mapN(traverse(args)(visitExp(_, env0, uenv0, tenv0))) {
        case as => NamedAst.Annotation(name, as, loc)
      }
  }

  /**
    * Translates the given weeded attribute to a named attribute.
    */
  private def visitAttribute(attr: WeededAst.Attribute, uenv0: UseEnv, tenv0: Map[String, Type.Var])(implicit flix: Flix): Validation[NamedAst.Attribute, NameError] = attr match {
    case WeededAst.Attribute(ident, tpe0, loc) =>
      mapN(visitType(tpe0, uenv0, tenv0)) {
        case tpe => NamedAst.Attribute(ident, tpe, loc)
      }
  }

  /**
    * Translates the given weeded formal parameter to a named formal parameter.
    */
  private def visitFormalParam(fparam: WeededAst.FormalParam, uenv0: UseEnv, tenv0: Map[String, Type.Var])(implicit flix: Flix): Validation[NamedAst.FormalParam, NameError] = fparam match {
    case WeededAst.FormalParam(ident, mod, optType, loc) =>
      // Generate a fresh variable symbol for the identifier.
      val freshSym = if (ident.name == "_")
        Symbol.freshVarSym("_")
      else
        Symbol.freshVarSym(ident)

      // Compute the type of the formal parameter or use the type variable of the symbol.
      val tpeVal = optType match {
        case None => NamedAst.Type.Var(freshSym.tvar, loc).toSuccess
        case Some(t) => visitType(t, uenv0, tenv0)
      }

      // Construct the formal parameter.
      mapN(tpeVal) {
        case tpe => NamedAst.FormalParam(freshSym, mod, tpe, loc)
      }
  }

  /**
    * Returns the given `freeVars` less the `boundVars`.
    */
  private def filterBoundVars(freeVars: List[Name.Ident], boundVars: List[Name.Ident]): List[Name.Ident] = {
    freeVars.filter(n1 => !boundVars.exists(n2 => n1.name == n2.name))
  }

  /**
    * Returns the class reflection object for the given `className`.
    */
  // TODO: Deprecated should be moved to resolver.
  private def lookupClass(className: String, loc: SourceLocation): Validation[Class[_], NameError] = try {
    Class.forName(className).toSuccess
  } catch {
    case ex: ClassNotFoundException => NameError.UndefinedNativeClass(className, loc).toFailure
  }

  /**
    * Returns `true` if the class types present in `expected` equals those in `actual`.
    */
  private def parameterTypeMatch(expected: List[Option[Class[_]]], actual: List[Class[_]]): Boolean =
    (expected zip actual) forall {
      case (None, _) => true
      case (Some(clazz1), clazz2) => clazz1 == clazz2
    }

  /**
    * Performs naming on the given formal parameters `fparam0` under the given environments `uenv0` and `tenv0`.
    */
  private def getFormalParams(fparams0: List[WeededAst.FormalParam], uenv0: UseEnv, tenv0: Map[String, Type.Var])(implicit flix: Flix): Validation[List[NamedAst.FormalParam], NameError] = {
    traverse(fparams0)(visitFormalParam(_, uenv0, tenv0))
  }

  /**
    * Gets the type params where their kind must be `*`.
    */
  private def getProperTypeParams(tparams0: WeededAst.TypeParams)(implicit flix: Flix): List[NamedAst.TypeParam] = tparams0 match {
    case TypeParams.Elided => Nil
    case TypeParams.Explicit(tparams) => tparams.map {
      case WeededAst.ConstrainedType(ident, classes) => NamedAst.TypeParam(ident, Type.freshVar(Kind.Star), classes, ident.loc)
    }
  }

  /**
    * Performs naming on the given type parameters `tparam0` from the given cases `cases`.
    */
  private def getTypeParamsFromCases(tparams0: WeededAst.TypeParams, cases: List[WeededAst.Case], loc: SourceLocation)(implicit flix: Flix): Validation[List[NamedAst.TypeParam], NameError] = {
    tparams0 match {
      case WeededAst.TypeParams.Elided => Nil.toSuccess // TODO allow implicit tparams?
      case WeededAst.TypeParams.Explicit(tparams) =>
        mapN(getImplicitTypeParamsFromCases(cases, loc)) {
          implicitTparams => getExplicitTypeParams(tparams, implicitTparams)
        }
    }
  }

  /**
    * Performs naming on the given type parameters `tparams0` from the given formal params `fparams` and overall type `tpe`.
    */
  private def getTypeParamsFromFormalParams(tparams0: WeededAst.TypeParams, fparams: List[WeededAst.FormalParam], tpe: WeededAst.Type, loc: SourceLocation, allowElision: Boolean)(implicit flix: Flix): Validation[List[NamedAst.TypeParam], NameError] ={
    tparams0 match {
      case WeededAst.TypeParams.Elided =>
        if (allowElision)
          getImplicitTypeParamsFromFormalParams(fparams, tpe, loc)
        else
          Nil.toSuccess
      case WeededAst.TypeParams.Explicit(tparams0) =>
        mapN(getImplicitTypeParamsFromFormalParams(fparams, tpe, loc)) {
          implicitTparams => getExplicitTypeParams(tparams0, implicitTparams)
        }
    }
  }

  /**
    * Returns the explicit type parameters from the given type parameter names and implicit type parameters.
    */
  private def getExplicitTypeParams(tparams0: List[WeededAst.ConstrainedType], implicitTparams: List[NamedAst.TypeParam])(implicit flix: Flix): List[NamedAst.TypeParam] = {
    val kindPerName = implicitTparams.map(param => param.name.name -> param.tpe.kind).toMap
    tparams0.map {
      case WeededAst.ConstrainedType(ident, classes) =>
        // Get the kind for each type variable from the implicit type params.
        // Use a kind variable if not found; this will be caught later by redundancy checks.
        val kind = kindPerName.getOrElse(ident.name, Kind.freshVar())
        val tvar = Type.freshVar(kind)
        // Remember the original textual name.
        tvar.setText(ident.name)
        NamedAst.TypeParam(ident, tvar, classes, ident.loc)
    }
  }

  /**
    * Returns the implicit type parameters constructed from the given enum cases.
    */
  private def getImplicitTypeParamsFromCases(cases: List[WeededAst.Case], loc: SourceLocation)(implicit flix: Flix): Validation[List[NamedAst.TypeParam], NameError] = {
    // Infer the kind for each type variable in the cases.
    val typeVarsWithKind = cases.flatMap {
      c => freeVarsWithKind(c.tpe)
    }
    freshTypeParamsWithKind(typeVarsWithKind, loc)
  }

  /**
    * Returns the implicit type parameters constructed from the given formal parameters and type.
    */
  private def getImplicitTypeParamsFromFormalParams(fparams: List[WeededAst.FormalParam], tpe: WeededAst.Type, loc: SourceLocation)(implicit flix: Flix): Validation[List[NamedAst.TypeParam], NameError] = {
    // Infer the kind for each free type variable in the signature.

    // Compute the type variables that occur in the formal parameters.
    val typeVarsWithKindArgs = fparams.flatMap {
      case WeededAst.FormalParam(_, _, Some(tpe), _) => freeVarsWithKind(tpe)
      case WeededAst.FormalParam(_, _, None, _) => List.empty
    }

    // Compute the type variables that occur in the overall type.
    // This may have some overlap with the free vars in the arguments.
    // That's ok; it does not affect the result.
    val typeVarsWithKindOverallType = freeVarsWithKind(tpe)

    // Compute the set of type variables.
    val typeVarsWithKind = (typeVarsWithKindOverallType ::: typeVarsWithKindArgs).distinct

    // Create a type param for each type variable
    freshTypeParamsWithKind(typeVarsWithKind, loc)
  }

  /**
    * Ensure each occurrence of the same name maps to the same kind.
    * Then create a type param for each name.
    */
  private def freshTypeParamsWithKind(typeVarsWithKind: List[(Name.Ident, Kind)], loc: SourceLocation)(implicit flix: Flix): Validation[List[NamedAst.TypeParam], NameError] = {
    // create a map of name -> (ident, kind), ensuring all kinds for a given name match
    val wildNameGenerator = Iterator.from(0).map("_" + _)
    val kindPerName = foldRight(typeVarsWithKind)(Map[String, (Name.Ident, Kind)]().toSuccess) {
      // Case 0: Wildcard
      case ((ident0, kind0), acc) if ident0.isWild => (acc + (wildNameGenerator.next() -> (ident0, kind0))).toSuccess
      case ((ident0, kind0), acc) => acc.get(ident0.name) match {
        // Case 1: name not found; add to map
        case None => (acc + (ident0.name -> (ident0, kind0))).toSuccess
        // Case 2: kind matches first kind; continue
        case Some((_, kind1)) if kind0 == kind1 => acc.toSuccess
        // Case 3: kinds do not match; error
        case Some((ident1, kind1)) => NameError.MismatchedTypeParamKinds(ident0.name, ident0.loc, kind0, ident1.loc, kind1).toFailure
      }
    }

    // for each name, create a type parameter with the appropriate kind
    mapN(kindPerName) {
      kindedNames =>
        kindedNames.values.toList.sortBy(_._1.name).map {
          case (id, kind) =>
            val tvar = Type.freshVar(kind) // use the kind we validated from the parameter context
            tvar.setText(id.name)
            NamedAst.TypeParam(id, tvar, Nil, loc) // use the id of the first occurrence of a tparam with this name
            // MATT may need to be not Nil for type constraints in enums
        }
    }
  }

  /**
    * Returns the implicit type parameters constructed from the given attributes.
    */
  private def getImplicitTypeParams(attrs: List[WeededAst.Attribute], loc: SourceLocation)(implicit flix: Flix): List[NamedAst.TypeParam] = {
    // Compute the type variables that occur in the formal parameters.
    val typeVars = attrs.foldLeft(Set.empty[String]) {
      case (acc, WeededAst.Attribute(_, tpe, _)) =>
        freeVars(tpe).foldLeft(acc) {
          case (innerAcc, ident) => innerAcc + ident.name
        }
    }

    // Construct a (sorted) list of type parameters.
    typeVars.toList.sorted.map {
      case name =>
        val ident = Name.Ident(SourcePosition.Unknown, name, SourcePosition.Unknown)
        // We use a kind variable since we do not know the kind of the type variable.
        val tvar = Type.freshVar(Kind.freshVar())
        tvar.setText(name)
        NamedAst.TypeParam(ident, tvar, Nil, loc)
    }
  }

  /**
    * Returns a variable environment constructed from the given formal parameters `fparams0`.
    */
  private def getVarEnv(fparams0: List[NamedAst.FormalParam]): Map[String, Symbol.VarSym] = {
    fparams0.foldLeft(Map.empty[String, Symbol.VarSym]) {
      case (macc, NamedAst.FormalParam(sym, mod, tpe, loc)) =>
        if (sym.isWild()) macc else macc + (sym.text -> sym)
    }
  }

  /**
    * Returns a type environment constructed from the given type parameters `tparams0`.
    */
  private def getTypeEnv(tparams0: List[NamedAst.TypeParam]): Map[String, Type.Var] = {
    tparams0.map(p => p.name.name -> p.tpe).toMap
  }

  /**
    * Returns the type scheme for the given type parameters `tparams0` and type `tpe` under the given environments `uenv0` and `tenv0`.
    */
  private def getDefScheme(tparams0: List[NamedAst.TypeParam], tpe: WeededAst.Type, uenv0: UseEnv, tenv0: Map[String, Type.Var])(implicit flix: Flix): Validation[NamedAst.Scheme, NameError] = {
    for {
      t <- visitType(tpe, uenv0, tenv0)
      tparams = tparams0.map(_.tpe)
      tconstrs = tparams0.flatMap(tparam => tparam.classes.map(NamedAst.TypeConstraint(_, tparam.tpe)))
    } yield NamedAst.Scheme(tparams, tconstrs, t)
  }

  /**
    * Returns the type scheme for the given type parameters `tparams0` and type `tpe` under the given environments `uenv0` and `tenv0`.
    */
  private def getSigScheme(tparams0: List[NamedAst.TypeParam], tpe: WeededAst.Type, uenv0: UseEnv, tenv0: Map[String, Type.Var], clazz: Name.QName, classTparam: NamedAst.TypeParam)(implicit flix: Flix): Validation[NamedAst.Scheme, NameError] = {
    for {
      t <- visitType(tpe, uenv0, tenv0)
      tparams = tparams0.map(_.tpe)
      // constrained as part of definition
      tconstrs1 = tparams0.flatMap(tparam => tparam.classes.map(NamedAst.TypeConstraint(_, tparam.tpe)))
      // constrained as class type parameters
      tconstrs2 = tparams0.find(_.name.name == classTparam.name.name).map(tparam => NamedAst.TypeConstraint(clazz, tparam.tpe)).toList
    } yield NamedAst.Scheme(tparams, tconstrs1 ++ tconstrs2, t)
  }

  /**
    * Disambiguate the given tag `tag0` with the given optional enum name `enumOpt0` under the given use environment `uenv0`.
    */
  private def getDisambiguatedTag(enumOpt0: Option[Name.QName], tag0: Name.Ident, uenv0: UseEnv): (Option[Name.QName], Name.Ident) = {
    enumOpt0 match {
      case None =>
        // Case 1: The tag is unqualified. Look it up in the use environment.
        uenv0.tags.get(tag0.name) match {
          case None =>
            // Case 1.1: The tag is unqualified and does not appear in the use environment. Leave it as is.
            (None, tag0)
          case Some((actualQName, actualTag)) =>
            // Case 1.2: The tag is unqualified and appears in the use environment. Use the actual qualified name and actual tag.
            (Some(actualQName), actualTag)
        }
      case Some(qname) =>
        // Case 2: The tag is qualified. Check if it fully-qualified.
        if (qname.isUnqualified) {
          // Case 2.1: The tag is only qualified by one name. Look it up in the use environment.
          uenv0.tpes.get(qname.ident.name) match {
            case None =>
              // Case 2.1.1: The qualified name is not in the use environment. Do not touch it.
              (Some(qname), tag0)
            case Some(actualQName) =>
              // Case 2.1.2: The qualified name is in the use environment. Use it instead.
              (Some(actualQName), tag0)
          }
        } else {
          // Case 2.2: The tag is fully-qualified. Do not touch it.
          (Some(qname), tag0)
        }
    }
  }

  /**
    * Builds a nested map of namespace -> name -> signature from the given class namespace map.
    */
  private def buildSigLookup(classes: Map[Name.NName, Map[String, NamedAst.Class]]): Map[Name.NName, Map[String, NamedAst.Sig]] = {
    def flatMapToSigs(classes: Map[String, NamedAst.Class]): Map[String, NamedAst.Sig] = {
      classes.flatMap {
        case (_, clazz) => clazz.sigs.map(sig => (sig.sym.name, sig))
      }
    }
    classes.foldLeft(Map.empty[Name.NName, Map[String, NamedAst.Sig]]) {
      case (acc, (namespace, classes1)) => acc + (namespace -> flatMapToSigs(classes1))
    }
  }

  /**
    * Merges the given `uses` into the given use environment `uenv0`.
    */
  private def mergeUseEnvs(uses: List[WeededAst.Use], uenv0: UseEnv): Validation[UseEnv, NameError] =
    Validation.fold(uses, uenv0) {
      case (uenv1, WeededAst.Use.UseClass(qname, alias, _)) =>
        val name = alias.name
        uenv1.classes.get(name) match {
          case None => uenv1.addClass(name, qname).toSuccess
          case Some(otherQName) => NameError.DuplicateUseClass(name, otherQName.loc, qname.loc).toFailure
        }
      case (uenv1, WeededAst.Use.UseDef(qname, alias, _)) =>
        val name = alias.name
        uenv1.defs.get(name) match {
          case None => uenv1.addDef(name, qname).toSuccess
          case Some(otherQName) => NameError.DuplicateUseDef(name, otherQName.loc, qname.loc).toFailure
        }
      case (uenv1, WeededAst.Use.UseTyp(qname, alias, _)) =>
        val name = alias.name
        uenv1.tpes.get(name) match {
          case None => uenv1.addTpe(name, qname).toSuccess
          case Some(otherQName) => NameError.DuplicateUseTyp(name, otherQName.loc, qname.loc).toFailure
        }
      case (uenv1, WeededAst.Use.UseTag(qname, tag, alias, loc)) =>
        val name = alias.name
        uenv1.tags.get(name) match {
          case None => uenv1.addTag(name, qname, tag).toSuccess
          case Some((otherQName, otherTag)) => NameError.DuplicateUseTag(name, otherTag.loc, tag.loc).toFailure
        }
    }

  /**
    * Companion object for the [[UseEnv]] class.
    */
  private object UseEnv {
    val empty: UseEnv = UseEnv(Map.empty, Map.empty, Map.empty, Map.empty)
  }

  /**
    * Represents an environment of "imported" names, including defs, types, and tags.
    */
  private case class UseEnv(classes: Map[String, Name.QName], defs: Map[String, Name.QName], tpes: Map[String, Name.QName], tags: Map[String, (Name.QName, Name.Ident)]) {
    /**
      * Binds the class name `s` to the qualified name `n`.
      */
    def addClass(s: String, n: Name.QName): UseEnv = copy(classes = classes + (s -> n))

    /**
      * Binds the def name `s` to the qualified name `n`.
      */
    def addDef(s: String, n: Name.QName): UseEnv = copy(defs = defs + (s -> n))

    /**
      * Binds the tpe name `s` to the qualified name `n`.
      */
    def addTpe(s: String, n: Name.QName): UseEnv = copy(tpes = tpes + (s -> n))

    /**
      * Binds the tag name `s` to the qualified name `n` and tag `t`.
      */
    def addTag(s: String, n: Name.QName, t: Name.Ident): UseEnv = copy(tags = tags + (s -> (n, t)))
  }

}
