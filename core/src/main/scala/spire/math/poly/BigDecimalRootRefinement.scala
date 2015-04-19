package spire.math
package poly

import scala.annotation.tailrec

import java.math.{ BigDecimal => JBigDecimal, RoundingMode, MathContext }

import spire.algebra._
import spire.std.bigInt._
import spire.syntax.signed._

sealed abstract class BigDecimalRootRefinement {
  def approximation: JBigDecimal
}

object BigDecimalRootRefinement {
  case class ExactRoot(root: JBigDecimal) extends BigDecimalRootRefinement {
    def approximation: JBigDecimal = root
  }

  case class Bounded(lowerBound: JBigDecimal, upperBound: JBigDecimal) extends BigDecimalRootRefinement {
    def approximation: JBigDecimal = lowerBound
  }

  case class BoundedLeft(lowerBound: Rational, upperBound: JBigDecimal) extends BigDecimalRootRefinement {
    def approximation: JBigDecimal = upperBound
  }

  case class BoundedRight(lowerBound: JBigDecimal, upperBound: Rational) extends BigDecimalRootRefinement {
    def approximation: JBigDecimal = lowerBound
  }

  // In this case we cannot find an approximation in the desired precision
  // that is within the bounds specified.
  case class OutOfBounds(lowerBound: Rational, upperBound: Rational, approximation: JBigDecimal) extends BigDecimalRootRefinement

  private implicit object JBigDecimalOrder extends Signed[JBigDecimal] with Order[JBigDecimal] {
    def signum(a: JBigDecimal): Int = a.signum
    def abs(a: JBigDecimal): JBigDecimal = a.abs
    def compare(x: JBigDecimal, y: JBigDecimal): Int = x compareTo y
  }

  def apply(poly: Polynomial[BigInt], lowerBound: Rational, upperBound: Rational, scale: Int): BigDecimalRootRefinement = {
    // TODO: See if Algebraic is faster.
    val qpoly = poly.map(Rational(_))
    def evalExact(x: JBigDecimal): JBigDecimal =
      qpoly(Rational(new BigDecimal(x, MathContext.UNLIMITED)))
        .toBigDecimal(scale, RoundingMode.UP)
        .bigDecimal

    val lb = lowerBound.toBigDecimal(scale, RoundingMode.CEILING).bigDecimal
    val ub = upperBound.toBigDecimal(scale, RoundingMode.FLOOR).bigDecimal

    val getEps: JBigDecimal => Int = { _ => scale }

    QIR(poly, lowerBound, upperBound, lb, ub, getEps, evalExact)
  }

  private val bits2dec: Double = log(2, 10)

  def apply(poly: Polynomial[BigInt], lowerBound: Rational, upperBound: Rational, mc: MathContext): BigDecimalRootRefinement = {
    // TODO: See if Algebraic is faster.
    val qpoly = poly.map(Rational(_))
    def evalExact(x: JBigDecimal): JBigDecimal =
      qpoly(Rational(new BigDecimal(x, MathContext.UNLIMITED)))
        .toBigDecimal(mc)
        .bigDecimal

    val lb = lowerBound.toBigDecimal(new MathContext(mc.getPrecision, RoundingMode.CEILING)).bigDecimal
    val ub = upperBound.toBigDecimal(new MathContext(mc.getPrecision, RoundingMode.FLOOR)).bigDecimal

    val getEps: JBigDecimal => Int = { x =>
      x.scale - ceil(x.unscaledValue.bitLength * bits2dec).toInt + mc.getPrecision + 1
    }

    QIR(poly, lowerBound, upperBound, lb, ub, getEps, evalExact)
  }

  /**
   * An implementation of "Quadratic Interval Refinement for Real Roots" by
   * John Abbot for `BigDecimal`.
   */
  private def QIR(
    poly: Polynomial[BigInt],
    lowerBound: Rational,
    upperBound: Rational,
    lb: JBigDecimal,
    ub: JBigDecimal,
    getEps: JBigDecimal => Int,
    evalExact: JBigDecimal => JBigDecimal
  ): BigDecimalRootRefinement = {
    val qpoly = poly.map(Rational(_))
    val qlb = Rational(new BigDecimal(lb, MathContext.UNLIMITED))
    val qub = Rational(new BigDecimal(ub, MathContext.UNLIMITED))

    // Returns true if there is a root in the open sub-interval (l, r).
    def hasRoot(l: Rational, r: Rational): Boolean =
      if (l != r) {
        val poly0 = qpoly
          .shift(l)
          .removeZeroRoots
          .reciprocal
          .shift((r - l).reciprocal)
          .removeZeroRoots
        // Ue Descartes' rule of signs to see if the root in the open interval
        // is actually in the sub interval (l, r).
        poly0.signVariations % 2 == 1
      } else {
        false
      }

    // QIR expects that (lx,rx) contain exactly 1 root and that they evaluate
    // to different, non-zero signs. However, we may find 1 or both of the
    // bounds to be 0, or to have "overshot" the root, so we have to either
    // find a good (lx, rx) or return an approximation early.
    def adjust(lx: JBigDecimal, lyOpt: Option[JBigDecimal], rx: JBigDecimal, ryOpt: Option[JBigDecimal]): BigDecimalRootRefinement = {
      def qlx = Rational(new BigDecimal(lx, MathContext.UNLIMITED))
      def qrx = Rational(new BigDecimal(rx, MathContext.UNLIMITED))

      if (lx.compareTo(rx) < 0) {
        val ly = lyOpt.getOrElse(evalExact(lx))
        val ry = ryOpt.getOrElse(evalExact(rx))
        if (ly.signum == 0) {
          if (qlx > lowerBound) {
            // We've "bumped" the lowerbound up to an exact root, coincidentally.
            ExactRoot(lx)
          } else {
            // We try to push lx up a bit to get the sign to change.
            adjust(lx.add(JBigDecimal.valueOf(1, getEps(lx))), Some(ly), rx, Some(ry))
          }
        } else if (ry.signum == 0) {
          if (qrx < upperBound) {
            // We've "bumped" the lowerbound up to an exact root, coincidentally.
            ExactRoot(rx)
          } else {
            // We try to push rx down a bit to get the sign to change.
            adjust(lx, Some(ly), rx.subtract(JBigDecimal.valueOf(1, getEps(rx))), Some(ry))
          }
        } else if (ry.signum == ly.signum) {
          // We've managed to overshoot the actual root, but since we're still
          // "in-bounds", we know it's in either the left cut off bit or the
          // right.
          if (hasRoot(lowerBound, qlx)) {
            BoundedLeft(lowerBound, lb)
          } else {
            BoundedRight(ub, upperBound)
          }
        } else {
          // Yay! We've successfully approximated the lower/upper bounds with
          // big decimal, while keeping the root within (lx, rx).
          QIR(lx, ly, rx, ry, getEps, evalExact)
        }
      } else {
        // We overshot a root.
        OutOfBounds(lowerBound, upperBound, lx)
      }
    }

    adjust(lb, None, ub, None)
  }

  /**
   * An implementation of "Quadratic Interval Refinement for Real Roots" by
   * John Abbot for `BigDecimal`.
   */
  private def QIR(
    lowerBound: JBigDecimal, lowerBoundValue: JBigDecimal,
    upperBound: JBigDecimal, upperBoundValue: JBigDecimal,
    getEps: JBigDecimal => Int, evalExact: JBigDecimal => JBigDecimal
  ): BigDecimalRootRefinement = {
    @tailrec
    def loop(
      lx: JBigDecimal,
      ly: JBigDecimal,
      rx: JBigDecimal,
      ry: JBigDecimal,
      n: Int
    ): BigDecimalRootRefinement = {
      val dy = ly.subtract(ry)
      val s = ly.divide(dy, n, RoundingMode.HALF_UP)
      val dx = rx.subtract(lx)
      val scale = max(getEps(lx), getEps(rx))
      val eps = JBigDecimal.valueOf(1, scale)
      if (dx.compareTo(eps) <= 0) {
        Bounded(
          lx.setScale(scale, RoundingMode.FLOOR),
          rx.setScale(scale, RoundingMode.CEILING)
        )
      } else {
        val delta = dx.multiply(s.ulp)
        val k = s.unscaledValue
        val x1 = lx.add(delta.multiply(new JBigDecimal(k))).setScale(scale, RoundingMode.HALF_UP)
        val y1 = evalExact(x1)
        val s1 = y1.sign
        if (s1 == ly.sign) {
          val x2 = x1.add(delta).setScale(scale, RoundingMode.CEILING)
          val y2 = evalExact(x2)
          val s2 = y2.sign
          if (s2 == s1) loop0(lx, ly, rx, ry)
          else if (s2 == ry.sign) loop(x1, y1, x2, y2, 2 * n)
          else ExactRoot(x2)
        } else if (s1 == ry.sign) {
          val x0 = x1.subtract(delta).setScale(scale, RoundingMode.FLOOR)
          val y0 = evalExact(x0)
          val s0 = y0.sign
          if (s0 == s1) loop0(lx, ly, rx, ry)
          else if (s0 == ly.sign) loop(x0, y0, x1, y1, 2 * n)
          else ExactRoot(x0)
        } else {
          ExactRoot(x1)
        }
      }
    }

    def bisect(
      x0: JBigDecimal,
      y0: JBigDecimal,
      x1: JBigDecimal,
      y1: JBigDecimal,
      x2: JBigDecimal,
      y2: JBigDecimal
    ): BigDecimalRootRefinement = {
      if (y0.signum == 0) ExactRoot(x0)
      else if (y1.signum == 0) ExactRoot(x1)
      else if (y2.signum == 0) ExactRoot(x2)
      else if (y0.sign != y1.sign) loop(x0, y0, x1, y1, 1)
      else loop(x1, y1, x2, y2, 1)
    }

    def loop0(
      x0: JBigDecimal,
      y0: JBigDecimal,
      x5: JBigDecimal,
      y5: JBigDecimal
    ): BigDecimalRootRefinement = {
      val dy = y0.subtract(y5)
      val k = y0
        .divide(dy, 1, RoundingMode.HALF_UP)
        .unscaledValue
        .intValue
      val eps = x5.subtract(x0).divide(new JBigDecimal(5))
      def eval(k: Int): (JBigDecimal, JBigDecimal) = {
        val x = new JBigDecimal(k).multiply(eps).add(x0)
        val y = evalExact(x)
        (x, y)
      }

      if (k < 5) {
        val (x2, y2) = eval(2)
        if (y2.sign != y0.sign) {
          val (x1, y1) = eval(1)
          bisect(x0, y0, x1, y1, x2, y2)
        } else {
          val (x3, y3) = eval(3)
          if (y3.sign == y5.sign) {
            loop(x2, y2, x3, y3, 1)
          } else {
            val (x4, y4) = eval(4)
            bisect(x3, y3, x4, y4, x5, y5)
          }
        }
      } else {
        val (x3, y3) = eval(3)
        if (y3.sign != y5.sign) {
          val (x4, y4) = eval(4)
          bisect(x3, y3, x4, y4, x5, y5)
        } else {
          val (x2, y2) = eval(2)
          if (y2.sign == y0.sign) {
            loop(x2, y2, x3, y3, 1)
          } else {
            val (x1, y1) = eval(1)
            bisect(x0, y0, x1, y1, x2, y2)
          }
        }
      }
    }

    val lx = lowerBound
    val ly = evalExact(lx)
    val rx = upperBound
    val ry = evalExact(rx)
    loop0(lx, ly, rx, ry)
  }
}
