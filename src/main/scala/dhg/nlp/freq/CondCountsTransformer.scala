package dhg.nlp.freq

import scala.collection.{ Map => CMap }
import scala.collection.breakOut
import scala.util.Random
import dhg.util.CollectionUtil._
import scalaz.Scalaz._
import dhg.util.math.RandBasis
import dhg.util.math.Rand

/**
 * A builder for conditional frequency distributions.  Stores counts (in a mutable
 * fashion) and allows counts to be added.  A distribution based on the
 * counts is generated by calling 'toFreqDist'.
 *
 * This is the top of a hierarchy designed for a modular approach to
 * frequency distribution building.  SimpleCondCountsTransformer serves as the basic
 * form of the counter; it stores and increments the actual counts.  Other
 * implementations of CondCountsTransformer will be count-transforming decorators
 * extending DelegatingCondCountsTransformer that wrap SimpleCondCountsTransformer or wrap
 * wrappers thereof.  Multiple layers of decoration allow various
 * transformations to be applied to the counts, and in varying orders.
 *
 * The operation of the system is such that counts, when added via the
 * top-most layer are passed, untouched, all the way to the base where they
 * are stored.  When toFreqDist is called, the counts are gathered via
 * recursive calls to resultCounts that travel down the layers to the bottom,
 * where the true counts are retrieved.  Each layer, starting from the bottom,
 * then applies its transformation and returns the modified counts to be
 * received by the higher layers.  Once the (modified) counts reach the top,
 * they are used to calculate the distribution.
 *
 * For example, the following code will create a CondCountsTransformer that, before
 * creating a distribution, will constrain its counts to those in validEntries
 * and then smooth the constrained counts:
 * {{{
 *   new SimpleSmoothingCondCountsTransformer(lambda,
 *     new ConstrainingCondCountsTransformer(validEntries, strict,
 *       new SimpleCondCountsTransformer()))
 * }}}
 *
 * Implementing classes should define:
 * <ul>
 *   <li> increment: Add to counts. Should simply forward to delegate.
 *   <li> resultCounts: Apply transformation to delegate's resultCounts.
 * </ul>
 *
 * @tparam A	the conditioning item being counted; P(B|A).
 * @tparam B	the conditioned item being counted; P(B|A).
 */
trait CondCountsTransformer[A, B] {
  final def apply[N](counts: CMap[A, CMap[B, N]])(implicit num: Numeric[N], rand: RandBasis = Rand): DefaultedCondFreqCounts[A, B] =
    this(DefaultedCondFreqCounts.fromMap(counts.mapVals(_.mapVals(num.toDouble)(breakOut): Map[B, Double]).toMap)(rand))

  def apply(counts: DefaultedCondFreqCounts[A, B]): DefaultedCondFreqCounts[A, B]
}

//////////////////////////////////////
// Passthrough implementation
//////////////////////////////////////

/**
 * CondCountsTransformer that performs no transformation
 */
case class PassthroughCondCountsTransformer[A, B]() extends CondCountsTransformer[A, B] {
  override def apply(counts: DefaultedCondFreqCounts[A, B]) = counts
}

//////////////////////////////////////
// Conditioned count transforming implementation
//////////////////////////////////////

/**
 * CondCountsTransformer that transforms the conditioned counts
 */
case class ConditionedCountsTransformer[A, B](bCountsTransformer: CountsTransformer[B], delegate: CondCountsTransformer[A, B]) extends CondCountsTransformer[A, B] {
  override def apply(counts: DefaultedCondFreqCounts[A, B]) = {
    val DefaultedCondFreqCounts(resultCounts) = delegate(counts)

    val allBs: Set[B] = resultCounts.flatMap(_._2.counts.keySet)(breakOut) // collect all Bs across all As

    DefaultedCondFreqCounts(
      resultCounts.mapVals {
        case m @ DefaultedMultinomial(c, d, t) =>
          val defaultCounts: Map[B, Double] = (allBs -- c.keySet).mapToVal(d)(breakOut)
          bCountsTransformer(DefaultedMultinomial(c |+| defaultCounts, d, t)(m.rand))
      })
  }
}

object ConditionedCountsTransformer {
  def apply[A, B](bCountsTransformer: CountsTransformer[B]): ConditionedCountsTransformer[A, B] =
    ConditionedCountsTransformer(bCountsTransformer, PassthroughCondCountsTransformer())
}

//////////////////////////////////////
// Constraining Implementation
//////////////////////////////////////

/**
 * CondCountsTransformer decorator that zeros out counts for entries not found in
 * validEntries.
 *
 * @param validEntries	zero out entries not found in this set
 * @param zeroDefaults	if false, unseen B values will left as their defaults and not zeroed
 * @param delegate		the delegate counter upon which the transformation is performed
 */
case class ConstrainingCondCountsTransformer[A, B](validEntries: Map[A, Set[B]], zeroDefaults: Boolean, delegate: CondCountsTransformer[A, B]) extends CondCountsTransformer[A, B] {
  override def apply(counts: DefaultedCondFreqCounts[A, B]) = {
    val DefaultedCondFreqCounts(resultCounts) = delegate(counts)
    if (zeroDefaults) {
      val zeroCountAs = DefaultedCondFreqCounts.fromMap(validEntries.mapVals(_ => Map[B, Double]())) // a count for every A in validEntries
      val allBs: Set[B] = (validEntries.values.flatten ++ resultCounts.values.flatMap(_.counts.keySet))(breakOut)
      val zeroCountBs: Map[B, Double] = allBs.mapToVal(0.0)(breakOut)
      DefaultedCondFreqCounts.fromMap(
        resultCounts.map {
          case (a, DefaultedMultinomial(aCounts, aDefaultCount, aTotalAddition)) =>
            validEntries.get(a) match {
              case Some(validBs) =>
                val filtered = aCounts.filterKeys(validBs)
                val defaults: Map[B, Double] = (validBs -- aCounts.keySet).mapToVal(aDefaultCount)(breakOut)
                a -> (filtered |+| defaults |+| zeroCountBs)
              case None =>
                a -> Map[B, Double]()
            }
        }) |+| zeroCountAs
    }
    else {
      val constrainedBs: Set[B] = validEntries.flatMap(_._2)(breakOut)
      DefaultedCondFreqCounts(
        resultCounts.map {
          case (a, bs @ DefaultedMultinomial(aCounts, aDefaultCount, aTotalAddition)) =>
            val zeros =
              validEntries.get(a) match {
                case Some(validBs) => (constrainedBs -- validBs)
                case None => constrainedBs
              }
            val filtered = aCounts ++ zeros.mapToVal(0.0)
            a -> DefaultedMultinomial(filtered, aDefaultCount, aTotalAddition)(bs.rand)
        })
    }
  }
}

object ConstrainingCondCountsTransformer {
  def apply[A, B](validEntries: Map[A, Set[B]], zeroDefaults: Boolean): CondCountsTransformer[A, B] =
    ConstrainingCondCountsTransformer(validEntries, zeroDefaults, PassthroughCondCountsTransformer[A, B]())

  def apply[A, B](validEntriesOpt: Option[Map[A, Set[B]]], zeroDefaults: Boolean, delegate: CondCountsTransformer[A, B]): CondCountsTransformer[A, B] =
    validEntriesOpt match {
      case Some(validEntries) => new ConstrainingCondCountsTransformer(validEntries, zeroDefaults, delegate)
      case None => delegate
    }

  def apply[A, B](validEntries: Option[Map[A, Set[B]]], zeroDefaults: Boolean): CondCountsTransformer[A, B] =
    ConstrainingCondCountsTransformer(validEntries, zeroDefaults, PassthroughCondCountsTransformer[A, B]())
}

//////////////////////////////////////
// Add-lambda smoothing implementation
//////////////////////////////////////

object AddLambdaSmoothingCondCountsTransformer {
  def apply[A, B](lambda: Double): ConditionedCountsTransformer[A, B] =
    ConditionedCountsTransformer(AddLambdaSmoothingCountsTransformer(lambda))
  def apply[A, B](lambda: Double, delegate: CondCountsTransformer[A, B]): ConditionedCountsTransformer[A, B] =
    ConditionedCountsTransformer(AddLambdaSmoothingCountsTransformer(lambda), delegate)
}

//////////////////////////////////////
// Eisner-Smoothing Implementation
//////////////////////////////////////

/**
 * CondCountsTransformer decorator that smoothes counts using the number of
 * single-count items to affect how much smoothing occurs; more single-count
 * items means higher likelihood of out-of-vocabulary items, and thus, more
 * smoothing should be allowed.
 *
 * This is taken from Jason Eisner's HMM homework.
 *
 * @param lambda					smoothing parameter for add-lambda smoothing
 * @param backoffCountsTransformer	used to compute the backoff probability
 */
class EisnerSmoothingCondCountsTransformer[A, B](lambda: Double, backoffCountsTransformer: CountsTransformer[B], delegate: CondCountsTransformer[A, B]) extends CondCountsTransformer[A, B] {
  override def apply(counts: DefaultedCondFreqCounts[A, B]) = {
    val DefaultedCondFreqCounts(resultCounts) = delegate(counts)

    // Compute backoff: probability of B regardless of A
    val totalBackoffCounts = resultCounts.map(_._2.counts).fold(Map())(_ |+| _)
    val transformedBackoffCounts = backoffCountsTransformer(totalBackoffCounts)
    val DefaultedMultinomial(backoffCounts, backoffDefaultCount, backoffTotalAddition) = transformedBackoffCounts
    val backoffTotal = backoffCounts.values.sum + backoffTotalAddition
    val backoffDist = backoffCounts.mapVals(_ / backoffTotal)
    val backoffDefault = backoffDefaultCount / backoffTotal

    val allBs: Set[B] = resultCounts.flatMap(_._2.counts.keySet)(breakOut) // collect all Bs across all As

    DefaultedCondFreqCounts(
      resultCounts.map {
        case (a, m @ DefaultedMultinomial(aCounts, aDefault, aTotalAdd)) =>
          // Replace any missing counts with the default
          val defaultCounts = (allBs -- aCounts.keySet).iterator.mapToVal(aDefault)
          val countsWithDefaults = aCounts ++ defaultCounts

          val numSingleCountItems = aCounts.count(c => 0.5 < c._2 && c._2 < 1.5)
          val smoothedLambda = lambda * (1e-100 + numSingleCountItems)
          val smoothedBackoff = backoffDist.mapVals(_ * smoothedLambda)
          val smoothedBackoffDefault = backoffDefault * smoothedLambda
          val smoothedCounts = countsWithDefaults |+| smoothedBackoff
          val smoothedDefaultCount = aDefault + smoothedBackoffDefault
          val smoothedTotalAddition = aTotalAdd + smoothedBackoffDefault

          (a, DefaultedMultinomial(smoothedCounts, smoothedDefaultCount, smoothedTotalAddition)(m.rand))
      })
  }
}

object EisnerSmoothingCondCountsTransformer {
  def apply[A, B](lambda: Double, backoffCountsTransformer: CountsTransformer[B]): EisnerSmoothingCondCountsTransformer[A, B] =
    new EisnerSmoothingCondCountsTransformer(lambda, backoffCountsTransformer, PassthroughCondCountsTransformer[A, B]())
  def apply[A, B](lambda: Double): EisnerSmoothingCondCountsTransformer[A, B] =
    EisnerSmoothingCondCountsTransformer(lambda, PassthroughCountsTransformer[B]())
  def apply[A, B](): EisnerSmoothingCondCountsTransformer[A, B] =
    EisnerSmoothingCondCountsTransformer(1.0)
}

//////////////////////////////////////
// Randomizing Implementation
//////////////////////////////////////

/**
 * This transformer adds a (possibly different) random number
 * between 1 and maxCount to each count returned by the delegate.
 */
case class RandomCondCountsTransformer[A, B](maxCount: Int, delegate: CondCountsTransformer[A, B]) extends CondCountsTransformer[A, B] {
  private val rand = new Random(0) // static seed ensures results are reproducible

  override def apply(counts: DefaultedCondFreqCounts[A, B]) = {
    val DefaultedCondFreqCounts(resultCounts) = delegate(counts)

    val allBs: Set[B] = resultCounts.flatMap(_._2.counts.keySet)(breakOut) // collect all Bs across all As

    DefaultedCondFreqCounts(
      resultCounts.mapVals {
        case m @ DefaultedMultinomial(c, d, t) =>
          val defaultCounts: Map[B, Double] = (allBs -- c.keySet).mapToVal(d)(breakOut)
          val scaled = (c |+| defaultCounts).mapVals(_ + rand.nextInt(maxCount + 1))
          DefaultedMultinomial(scaled, d, t)(m.rand)
      })
  }
}

object RandomCondCountsTransformer {
  def apply[A, B](maxCount: Int): RandomCondCountsTransformer[A, B] =
    RandomCondCountsTransformer(maxCount, PassthroughCondCountsTransformer())
}
