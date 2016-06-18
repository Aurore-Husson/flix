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

package ca.uwaterloo.flix.runtime

import ca.uwaterloo.flix.api.{IValue, WrappedValue}
import ca.uwaterloo.flix.language.ast.ExecutableAst._
import ca.uwaterloo.flix.language.ast.{Ast, ExecutableAst, Symbol}
import ca.uwaterloo.flix.runtime.datastore.DataStore
import ca.uwaterloo.flix.runtime.debugger.RestServer
import ca.uwaterloo.flix.util._

import java.util.concurrent._
import java.util.ArrayList

import scala.annotation.tailrec
import scala.collection.mutable

object Solver {

  /**
    * A case class representing a solver context.
    */
  // TODO: Deprecated
  case class SolverContext(root: ExecutableAst.Root, options: Options)

}

/**
  * Flix Parallel Fixed Point Solver.
  *
  * Based on a variant of semi-naive evaluation.
  * The solver iteratively evaluates every rule in the program to infer new facts.
  * When no more new facts are discovered, the fixed point has been found, and computation terminates.
  *
  * The solver is parallel. At a high level:
  * Rules are evaluated in parallel to infer new facts. These facts are collected into a set.
  * When all rules have been evaluated, the database is updated with the new facts.
  * This is also performed in parallel.
  *
  * Importantly, the datastore is *never* updated while rules are being evaluated.
  */
class Solver(implicit val sCtx: Solver.SolverContext) {

  /**
    * The datastore holds the facts in all relations and lattices in the program.
    *
    * Reading from the datastore is guaranteed to be thread-safe and can be performed by multiple threads concurrently.
    *
    * Writing to the datastore is, in general, not thread-safe:
    * Each relation/lattice may be concurrently updated, but
    * no concurrent writes may occur for the *same* relation/lattice.
    */
  val dataStore = new DataStore[AnyRef]()

  /**
    * The thread pool where rule evaluation takes place.
    *
    * Note: Evaluation of a rule only *reads* from the datastore.
    * Thus it is safe to evaluate multiple rules concurrently.
    */
  val readers = mkThreadPool()

  /**
    * The thread pool where updates to the data store takes places.
    *
    * Note: A writer *must not* concurrently write to the same relation/lattice.
    * However, different relations/lattices can be updated concurrently.
    */
  val writes = mkThreadPool()

  /**
    * The worklist of rules (and their initial environments) pending re-evaluation.
    */
  val worklist = new mutable.ArrayStack[(Constraint.Rule, mutable.Map[String, AnyRef])]



  /**
    * A collection of facts which have been inferred but not yet stored in the database.
    *
    * Updated by multiple threads. Must be accessed behind a lock.
    */
  // TODO: Deprecated
  val pendingFacts = new mutable.ListBuffer[(Symbol.TableSym, Array[AnyRef], Boolean)]()

  /**
    * The performance monitor.
    */
  val monitor = new Monitor(this)

  /**
    * The current state of the solver.
    */
  @volatile
  var paused: Boolean = false

  /**
    * The model (if it exists).
    */
  @volatile
  var model: Model = null

  /**
    * Returns the number of elements in the worklist.
    */
  def getQueueSize = worklist.length

  /**
    * Returns the number of facts in the database.
    */
  def getNumberOfFacts: Int = dataStore.numberOfFacts

  /**
    * Pauses the solver.
    */
  def pause(): Unit = synchronized {
    paused = true
  }

  /**
    * Resumes the fixpoint computation.
    */
  def resume(): Unit = synchronized {
    paused = false
    notify()
  }

  /**
    * Solves the current Flix program.
    */
  def solve(): Model = {

    if (sCtx.options.debugger == Debugger.Enabled) {
      monitor.start()

      val restServer = new RestServer(this)
      restServer.start()

      val shell = new Shell(this)
      shell.start()
    }

    // measure the time elapsed.
    val t = System.nanoTime()

    // evaluate all facts.
    for (fact <- sCtx.root.facts) {
      evalHead(fact.head, mutable.Map.empty, enqueue = false)
    }

    // add all rules to the worklist (under empty environments).
    for (rule <- sCtx.root.rules) {
      worklist.push((rule, mutable.Map.empty))
    }

    // iterate until fixpoint.
    while (worklist.nonEmpty) {
      if (paused) {
        synchronized {
          wait()
        }
      }

      /*
       * Process every (rule, environment) pair in a separate task.
       *
       * Clear the work list.
       * Execute all the tasks in parallel, and
       * Await the result of *all* tasks.
       */
      val tasks = new ArrayList[Callable[Unit]]()
      for ((rule, env) <- worklist) {
        val task = new Callable[Unit] {
          def call(): Unit = {
            evalBody(rule, env)
          }
        }
        tasks.add(task)
      }
      worklist.clear()

      /*
       * Executes the given tasks, returning a list of futures holding their status and results when all complete.
       */
      readers.invokeAll(tasks)

      /*
       * Stores all the pending facts into the datastore.
       *
       * Enqueues (rule, environment) pairs as a side-effect.
       *
       * Must be executed by a single-thread.
       */
      for ((sym, fact, enqueue) <- pendingFacts) {
        inferredFact(sym, fact, enqueue)
      }
      pendingFacts.clear()

    }

    /*
     * Shutdown the thread pool.
     */
    readers.shutdown()

    // computed elapsed time.
    val elapsed = System.nanoTime() - t

    if (sCtx.options.verbosity != Verbosity.Silent) {
      val solverTime = elapsed / 1000000
      val initialFacts = sCtx.root.facts.length
      val totalFacts = dataStore.numberOfFacts
      val throughput = (1000 * totalFacts) / (solverTime + 1)
      Console.println(f"Successfully solved in $solverTime%,d msec.")
      Console.println(f"Initial Facts: $initialFacts%,d. Total Facts: $totalFacts%,d.")
      Console.println(f"Throughput: $throughput%,d facts per second.")
    }

    if (sCtx.options.debugger == Debugger.Enabled) {
      monitor.stop()
    }

    // construct the model.
    val definitions = sCtx.root.constants.foldLeft(Map.empty[Symbol.Resolved, () => AnyRef]) {
      case (macc, (sym, defn)) =>
        if (defn.formals.isEmpty)
          macc + (sym -> (() => Interpreter.evalCall(defn, Array.empty, sCtx.root)))
        else
          macc + (sym -> (() => throw new InternalRuntimeException("Unable to evalaute non-constant top-level definition.")))
    }

    val relations = dataStore.relations.foldLeft(Map.empty[Symbol.TableSym, Iterable[List[AnyRef]]]) {
      case (macc, (sym, relation)) =>
        val table = relation.scan.toIterable.map(_.toList)
        macc + ((sym, table))
    }
    val lattices = dataStore.lattices.foldLeft(Map.empty[Symbol.TableSym, Iterable[(List[AnyRef], List[AnyRef])]]) {
      case (macc, (sym, lattice)) =>
        val table = lattice.scan.toIterable.map {
          case (keys, values) => (keys.toArray.toList, values.toList)
        }
        macc + ((sym, table))
    }
    model = new Model(sCtx.root, definitions, relations, lattices)
    model
  }

  def getModel: Model = model

  // TODO: Move
  def getRuleStats: List[(ExecutableAst.Constraint.Rule, Int, Long)] =
    sCtx.root.rules.toSeq.sortBy(_.elapsedTime).reverse.map {
      case r => (r, r.hitcount, r.elapsedTime)
    }.toList

  /**
    * Processes an inferred `fact` for the relation or lattice with the symbol `sym`.
    */
  def inferredFact(sym: Symbol.TableSym, fact: Array[AnyRef], enqueue: Boolean): Unit = sCtx.root.tables(sym) match {
    case r: ExecutableAst.Table.Relation =>
      val changed = dataStore.relations(sym).inferredFact(fact)
      if (changed && enqueue) {
        dependencies(r.sym, fact)
      }

    case l: ExecutableAst.Table.Lattice =>
      val changed = dataStore.lattices(sym).inferredFact(fact)
      if (changed && enqueue) {
        dependencies(l.sym, fact)
      }
  }

  /**
    * Adds the given `fact` as pending for the relation or lattice with the symbol `sym`.
    */
  def newPendingFact(sym: Symbol.TableSym, fact: Array[AnyRef], enqueue: Boolean): Unit = {
    pendingFacts.synchronized {
      pendingFacts += ((sym, fact, enqueue))
    }
  }

  /**
    * Evaluates the given head predicate `p` under the given environment `env0`.
    */
  def evalHead(p: Predicate.Head, env0: mutable.Map[String, AnyRef], enqueue: Boolean): Unit = p match {
    case p: Predicate.Head.Table =>
      val terms = p.terms
      val fact = new Array[AnyRef](p.arity)
      var i = 0
      while (i < fact.length) {
        fact(i) = Interpreter.evalHeadTerm(terms(i), sCtx.root, env0.toMap)
        i = i + 1
      }
      if (!enqueue) {
        inferredFact(p.sym, fact, enqueue)
      } else {
        newPendingFact(p.sym, fact, enqueue)
      }

  }


  /**
    * Evaluates the body of the given `rule` under the given initial environment `env0`.
    */
  def evalBody(rule: Constraint.Rule, env0: mutable.Map[String, AnyRef]): Unit = {
    val t = System.nanoTime()

    cross(rule, rule.tables, env0)

    rule.elapsedTime += System.nanoTime() - t
    rule.hitcount += 1
  }

  /**
    * Computes the cross product of all collections in the body.
    */
  def cross(rule: Constraint.Rule, ps: List[Predicate.Body.Table], row: mutable.Map[String, AnyRef]): Unit = ps match {
    case Nil =>
      // cross product complete, now filter
      loop(rule, rule.loops, row)
    case (p: Predicate.Body.Table) :: xs =>
      // lookup the relation or lattice.
      val table = sCtx.root.tables(p.sym) match {
        case r: Table.Relation => dataStore.relations(p.sym)
        case l: Table.Lattice => dataStore.lattices(p.sym)
      }

      // evaluate all terms in the predicate.
      val pat = new Array[AnyRef](p.arity)
      var i = 0
      while (i < pat.length) {
        pat(i) = eval(p.terms(i), row)
        i = i + 1
      }

      // lookup all matching rows.
      for (matchedRow <- table.lookup(pat)) {
        // copy the environment for every row.
        val newRow = row.clone()

        var i = 0
        while (i < matchedRow.length) {
          val varName = p.index2var(i)
          if (varName != null)
            newRow.update(varName, matchedRow(i))
          i = i + 1
        }

        // compute the cross product of the remaining
        // collections under the new environment.
        cross(rule, xs, newRow)
      }
  }

  /**
    * Unfolds the given loop predicates `ps` over the initial `row`.
    */
  def loop(rule: Constraint.Rule, ps: List[Predicate.Body.Loop], row: mutable.Map[String, AnyRef]): Unit = ps match {
    case Nil => filter(rule, rule.filters, row)
    case Predicate.Body.Loop(name, term, _, _, _) :: rest =>
      val result = Value.cast2set(Interpreter.evalHeadTerm(term, sCtx.root, row.toMap))
      for (x <- result) {
        val newRow = row.clone()
        newRow.update(name.name, x)
        loop(rule, rest, newRow)
      }
  }

  /**
    * Filters the given `row` through all filter functions in the body.
    */
  @tailrec
  private def filter(rule: Constraint.Rule, ps: List[Predicate.Body.ApplyFilter], row: mutable.Map[String, AnyRef]): Unit = ps match {
    case Nil =>
      // filter with hook functions
      filterHook(rule, rule.filterHooks, row)
    case (pred: Predicate.Body.ApplyFilter) :: xs =>
      val defn = sCtx.root.constants(pred.name)
      val args = new Array[AnyRef](pred.terms.length)
      var i = 0
      while (i < args.length) {
        args(i) = Interpreter.evalBodyTerm(pred.terms(i), sCtx.root, row.toMap)
        i = i + 1
      }
      val result = Interpreter.evalCall(defn, args, sCtx.root, row.toMap)
      if (Value.cast2bool(result))
        filter(rule, xs, row)
  }

  /**
    * Filters the given `row` through all filter hook functions in the body.
    */
  @tailrec
  private def filterHook(rule: Constraint.Rule, ps: List[Predicate.Body.ApplyHookFilter], row: mutable.Map[String, AnyRef]): Unit = ps match {
    case Nil =>
      // filter complete, now check disjointness
      disjoint(rule, rule.disjoint, row)
    case (pred: Predicate.Body.ApplyHookFilter) :: xs =>

      val args = new Array[AnyRef](pred.terms.length)
      var i = 0
      while (i < args.length) {
        args(i) = Interpreter.evalBodyTerm(pred.terms(i), sCtx.root, row.toMap)
        i = i + 1
      }

      pred.hook match {
        case Ast.Hook.Safe(name, inv, tpe) =>
          val wargs = args map {
            case arg => new WrappedValue(arg): IValue
          }
          val result = inv.apply(wargs).getUnsafeRef
          if (Value.cast2bool(result)) {
            filterHook(rule, xs, row)
          }
        case Ast.Hook.Unsafe(name, inv, tpe) =>
          val result = inv.apply(args)
          if (Value.cast2bool(result)) {
            filterHook(rule, xs, row)
          }
      }
  }

  /**
    * Filters the given `row` through all disjointness filters in the body.
    */
  @tailrec
  private def disjoint(rule: Constraint.Rule, ps: List[Predicate.Body.NotEqual], row: mutable.Map[String, AnyRef]): Unit = ps match {
    case Nil =>
      // rule body complete, evaluate the head.
      evalHead(rule.head, row, enqueue = true)
    case Predicate.Body.NotEqual(ident1, ident2, _, _, _) :: xs =>
      val value1 = row(ident1.name)
      val value2 = row(ident2.name)
      if (value1 != value2) {
        disjoint(rule, xs, row)
      }
  }

  /**
    * Evaluates the given body term `t` to a value.
    *
    * Returns `null` if the term is a free variable.
    */
  def eval(t: ExecutableAst.Term.Body, env: mutable.Map[String, AnyRef]): AnyRef = t match {
    case t: ExecutableAst.Term.Body.Wildcard => null
    case t: ExecutableAst.Term.Body.Var => env.getOrElse(t.ident.name, null)
    case t: ExecutableAst.Term.Body.Exp => Interpreter.eval(t.e, sCtx.root, env.toMap)
  }

  /**
    * Returns all dependencies of the given symbol `sym` along with an environment.
    */
  def dependencies(sym: Symbol.TableSym, fact: Array[AnyRef]): Unit = {

    def unify(pat: Array[String], fact: Array[AnyRef], limit: Int): mutable.Map[String, AnyRef] = {
      val env = mutable.Map.empty[String, AnyRef]
      var i = 0
      while (i < limit) {
        val varName = pat(i)
        if (varName != null)
          env.update(varName, fact(i))
        i = i + 1
      }
      env
    }

    val table = sCtx.root.tables(sym)
    for ((rule, p) <- sCtx.root.dependenciesOf(sym)) {
      table match {
        case r: ExecutableAst.Table.Relation =>
          // unify all terms with their values.
          val env = unify(p.index2var, fact, fact.length)
          if (env != null) {
            worklist += ((rule, env))
          }
        case l: ExecutableAst.Table.Lattice =>
          // unify only key terms with their values.
          val numberOfKeys = l.keys.length
          val env = unify(p.index2var, fact, numberOfKeys)
          if (env != null) {
            worklist += ((rule, env))
          }
      }
    }
  }

  /**
    * Returns a new thread pool configured to use the appropriate number of threads.
    */
  private def mkThreadPool(): ExecutorService = sCtx.options.parallel match {
    // Case 1: Parallel execution enabled. Use all available processors.
    case Parallel.Enable => Executors.newFixedThreadPool(Runtime.getRuntime.availableProcessors())
    // Case 2: Parallel execution disabled. Use a single thread.
    case Parallel.Disable => Executors.newSingleThreadExecutor()
  }


}
