package dhg.nlp.tag

import scala.collection.mutable.Buffer

import dhg.util.CollectionUtil._
import scalaz._
import Scalaz._

class TaggerEvaluator[Sym, Tag] {

  def evaluate(
    taggerOutput: TraversableOnce[Vector[(Sym, Tag)]],
    goldData: TraversableOnce[Vector[(Sym, Tag)]],
    tagDict: TagDict[Sym, Tag]): ScoreResults[Sym, Tag] = {

    var correct = 0
    var total = 0
    var knownCorrect = 0
    var knownTotal = 0
    var unkCorrect = 0
    var unkTotal = 0
    var mistakes = List[(Tag, Tag)]()

    for ((result, gold) <- taggerOutput.toIterator zipSafe goldData) {
      assert(result.size == gold.size, "sequence length in result does not match gold: %s != %s".format(result, gold))
      for (((rsltSym, rsltTag), (goldSym, goldTag)) <- result zipSafe gold) {
        assert(rsltSym == goldSym, "result sentence and gold sentence are different: %s != %s".format(result.map(_._1), gold.map(_._1)))

        if (rsltTag == goldTag)
          correct += 1
        else
          mistakes ::= ((goldTag, rsltTag))
        total += 1

        if (tagDict.contains(goldSym) /*&& tagDict(goldSym).contains(goldTag)*/ ) {
          if (rsltTag == goldTag)
            knownCorrect += 1
          knownTotal += 1
        }
        else {
          if (rsltTag == goldTag)
            unkCorrect += 1
          unkTotal += 1
        }
      }
    }

    ScoreResults(correct, total, knownCorrect, knownTotal, unkCorrect, unkTotal, mistakes.counts)
  }

}

case class ScoreResults[Sym, Tag](
  correct: Int, total: Int,
  knownCorrect: Int, knownTotal: Int,
  unkCorrect: Int, unkTotal: Int,
  mistakes: Map[(Tag, Tag), Int]) {

  def acc(count: Int, total: Int) = count.toDouble / total
  def accStr(count: Int, total: Int) = "%.2f (%d/%d)".format(acc(count, total) * 100, count, total)

  override def toString = {
    val sb = Buffer[String]()
    sb.append("Total:   " + accStr(correct, total))
    sb.append("Known:   " + accStr(knownCorrect, knownTotal))
    sb.append("Unknown: " + accStr(unkCorrect, unkTotal))
    sb.append("Common Mistakes:")
    sb.append("#Err     Gold      Model")
    for (((goldTag, rsltTag), count) <- mistakes.toVector.sortBy(-_._2).take(5))
      sb.append("%-8d %-8s %-8s".format(count, goldTag, rsltTag))
    sb.mkString("\n")
  }

  def totalAcc = acc(correct, total)
  def knownAcc = acc(knownCorrect, knownTotal)
  def unknownAcc = acc(unkCorrect, unkTotal)

  def +(other: ScoreResults[Sym, Tag]) = {
    ScoreResults[Sym, Tag](
      correct + other.correct, total + other.total,
      knownCorrect + other.knownCorrect, knownTotal + other.knownTotal,
      unkCorrect + other.unkCorrect, unkTotal + other.unkTotal,
      mistakes |+| other.mistakes)
  }

}

object ScoreResults {
  def empty[Sym, Tag] = ScoreResults(0, 0, 0, 0, 0, 0, Map[(Tag, Tag), Int]())
}
