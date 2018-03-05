package cwl

import cats.syntax.validated._
import common.validation.ErrorOr.{ErrorOr, ShortCircuitingFlatMap}
import common.validation.Validation._
import cwl.InitialWorkDirFileGeneratorExpression._
import cwl.InitialWorkDirRequirement.IwdrListingArrayEntry
import shapeless.Poly1
import wom.expression.{IoFunctionSet, WomExpression}
import wom.types._
import wom.values._

import scala.concurrent.Await
import scala.concurrent.duration.Duration

trait CwlWomExpression extends WomExpression {

  def cwlExpressionType: WomType

  override def evaluateType(inputTypes: Map[String, WomType]): ErrorOr[WomType] = cwlExpressionType.validNel

  def expressionLib: ExpressionLib

  def evaluate(inputs: Map[String, WomValue], parameterContext: ParameterContext, expression: Expression, expressionLib: ExpressionLib): ErrorOr[WomValue] =
    expression.
      fold(EvaluateExpression).
      apply(parameterContext, expressionLib)
}

case class ECMAScriptWomExpression(expression: Expression,
                                   override val inputs: Set[String],
                                   override val expressionLib: ExpressionLib) extends CwlWomExpression {
  val cwlExpressionType = WomAnyType

  override def sourceString = expression match {
    case Expression.ECMAScriptExpression(s) => s.value
    case Expression.ECMAScriptFunction(s) => s.value
  }

  override def evaluateValue(inputValues: Map[String, WomValue], ioFunctionSet: IoFunctionSet) = {
    val pc = ParameterContext(inputValues)
    evaluate(inputValues, pc, expression, expressionLib)
  }

  override def evaluateFiles(inputTypes: Map[String, WomValue], ioFunctionSet: IoFunctionSet, coerceTo: WomType) = Set.empty[WomFile].validNel
}

final case class InitialWorkDirFileGeneratorExpression(entry: IwdrListingArrayEntry, expressionLib: ExpressionLib) extends CwlWomExpression {
  override def cwlExpressionType: WomType = WomSingleFileType
  override def sourceString: String = entry.toString

  override def evaluateValue(inputValues: Map[String, WomValue], ioFunctionSet: IoFunctionSet): ErrorOr[WomValue] = {
    val parameterContext = ParameterContext(inputValues)
    entry.fold(InitialWorkDirFilePoly).apply(ioFunctionSet, parameterContext, expressionLib)
  }

  override def evaluateFiles(inputTypes: Map[String, WomValue],
                             ioFunctionSet: IoFunctionSet,
                             coerceTo: WomType): ErrorOr[Set[WomFile]] = {
    "Programmer error: Shouldn't use InitialWorkDirRequirement listing to find output files.".invalidNel
  }

  /**
    * We already get all of the task inputs when evaluating, and we don't need to highlight anything else
    */
  override def inputs: Set[String] = Set.empty
}

object InitialWorkDirFileGeneratorExpression {
  type InitialWorkDirFileEvaluator = (IoFunctionSet, ParameterContext, ExpressionLib) => ErrorOr[WomValue]

  /**
    * Converts an InitialWorkDir.
    *
    * TODO: Review against the spec. Especially for Dirent values. For example:
    *
    * "If the value is an expression that evaluates to a Dirent object, this indicates that the File or Directory in
    * entry should be added to the designated output directory with the name in entryname."
    *
    * - http://www.commonwl.org/v1.0/CommandLineTool.html#InitialWorkDirRequirement
    * - http://www.commonwl.org/v1.0/CommandLineTool.html#Dirent
    */
  object InitialWorkDirFilePoly extends Poly1 {
    implicit val caseExpressionDirent: Case.Aux[ExpressionDirent, InitialWorkDirFileEvaluator] = {
      at { expressionDirent =>
        (ioFunctionSet, parameterContext, expressionLib) => {
          val entryEvaluation = ExpressionEvaluator.eval(expressionDirent.entry, parameterContext, expressionLib)
          entryEvaluation flatMap {
            case womFile: WomFile =>
              val errorOrEntryName: ErrorOr[String] = expressionDirent.entryname match {
                case Some(actualEntryName) => actualEntryName.fold(EntryNamePoly).apply(parameterContext, expressionLib)
                case None => womFile.value.split('/').last.valid
              }
              errorOrEntryName flatMap { entryName =>
                validate(Await.result(ioFunctionSet.copyFile(womFile.value, entryName), Duration.Inf))
              }
            case other => for {
              coerced <- WomStringType.coerceRawValue(other).toErrorOr
              contentString = coerced.asInstanceOf[WomString].value
              // We force the entryname to be specified, and then evaluate it:
              entryNameStringOrExpression <- expressionDirent.entryname.toErrorOr(
                "Invalid dirent: Entry was a string but no file name was supplied")
              entryName <- entryNameStringOrExpression.fold(EntryNamePoly).apply(parameterContext, expressionLib)
              writeFile = ioFunctionSet.writeFile(entryName, contentString)
              writtenFile <- validate(Await.result(writeFile, Duration.Inf))
            } yield writtenFile
          }
        }
      }
    }

    implicit val caseStringDirent: Case.Aux[StringDirent, InitialWorkDirFileEvaluator] = {
      at {
        stringDirent => {
          (ioFunctionSet, parameterContext, expressionLib) =>
            for {
              entryName <- stringDirent.entryname.fold(EntryNamePoly).apply(parameterContext, expressionLib)
              contentString = stringDirent.entry
              writeFile = ioFunctionSet.writeFile(entryName, contentString)
              writtenFile <- validate(Await.result(writeFile, Duration.Inf))
            } yield writtenFile
        }
      }
    }

    implicit val caseExpression: Case.Aux[Expression, InitialWorkDirFileEvaluator] = {
      at { expression =>
        (_, parameterContext, expressionLib) => {
          // A single expression which must evaluate to an array of Files
          val expressionEvaluation = ExpressionEvaluator.eval(expression, parameterContext, expressionLib)

          expressionEvaluation flatMap {
            case array: WomArray if WomArrayType(WomSingleFileType).coercionDefined(array) =>
              WomArrayType(WomSingleFileType).coerceRawValue(array).toErrorOr
            case file: WomFile => file.valid
            case other =>
              val error = "InitialWorkDirRequirement listing expression must be Array[File] but got %s: %s"
                .format(other, other.womType.toDisplayString)
              error.invalidNel
          }
        }
      }
    }

    implicit val caseString: Case.Aux[String, InitialWorkDirFileEvaluator] = {
      at { string =>
        (_, _, _) => {
          WomSingleFile(string).valid
        }
      }
    }

    implicit val caseStringOrExpression: Case.Aux[StringOrExpression, InitialWorkDirFileEvaluator] = {
      at {
        _.fold(this)
      }
    }

    implicit val caseFile: Case.Aux[File, InitialWorkDirFileEvaluator] = {
      at { file =>
        (_, _, _) => {
          file.asWomValue
        }
      }
    }

    implicit val caseDirectory: Case.Aux[Directory, InitialWorkDirFileEvaluator] = {
      at { directory =>
        (_, _, _) => {
          directory.asWomValue
        }
      }
    }

  }

  type EntryNameEvaluator = (ParameterContext, ExpressionLib) => ErrorOr[String]

  object EntryNamePoly extends Poly1 {
    implicit val caseString: Case.Aux[String, EntryNameEvaluator] = {
      at {
        string => {
          (_, _) =>
            string.valid
        }
      }
    }

    implicit val caseExpression: Case.Aux[Expression, EntryNameEvaluator] = {
      at {
        expression => {
          (parameterContext, expressionLib) =>
            for {
              entryNameExpressionEvaluated <- ExpressionEvaluator.eval(expression, parameterContext, expressionLib)
              entryNameValidated <- mustBeString(entryNameExpressionEvaluated)
            } yield entryNameValidated
        }
      }
    }

    private def mustBeString(womValue: WomValue): ErrorOr[String] = {
      womValue match {
        case WomString(s) => s.valid
        case other => WomStringType.coerceRawValue(other).map(_.asInstanceOf[WomString].value).toErrorOr
      }
    }
  }

}
