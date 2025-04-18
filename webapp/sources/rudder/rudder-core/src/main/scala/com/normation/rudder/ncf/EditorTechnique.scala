/*
 *************************************************************************************
 * Copyright 2017 Normation SAS
 *************************************************************************************
 *
 * This file is part of Rudder.
 *
 * Rudder is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * In accordance with the terms of section 7 (7. Additional Terms.) of
 * the GNU General Public License version 3, the copyright holders add
 * the following Additional permissions:
 * Notwithstanding to the terms of section 5 (5. Conveying Modified Source
 * Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
 * Public License version 3, when you create a Related Module, this
 * Related Module is not considered as a part of the work and may be
 * distributed under the license agreement of your choice.
 * A "Related Module" means a set of sources files including their
 * documentation that, without modification of the Source Code, enables
 * supplementary functions or services in addition to those offered by
 * the Software.
 *
 * Rudder is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

 *
 *************************************************************************************
 */

package com.normation.rudder.ncf

import better.files.File
import cats.data.NonEmptyList
import cats.syntax.functor.*
import com.normation.cfclerk.domain.ReportingLogic
import com.normation.errors.*
import com.normation.inventory.domain.AgentType
import com.normation.inventory.domain.Version
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.ncf.Constraint.CheckResult
import com.normation.rudder.ncf.Constraint.Constraint
import com.normation.rudder.services.policies.PropertyParser
import com.normation.rudder.services.policies.PropertyParserTokens.CharSeq
import com.normation.rudder.services.policies.PropertyParserTokens.NodeAccessor
import com.normation.rudder.services.policies.PropertyParserTokens.NonRudderVar
import com.normation.rudder.services.policies.PropertyParserTokens.Param
import com.normation.rudder.services.policies.PropertyParserTokens.Property
import com.normation.rudder.services.policies.PropertyParserTokens.RudderEngine
import com.normation.rudder.services.policies.PropertyParserTokens.Token
import com.normation.rudder.services.policies.PropertyParserTokens.UnsafeRudderVar
import java.util.regex.Pattern
import zio.ZIO
import zio.json.SnakeCase
import zio.json.ast.Json
import zio.json.jsonDiscriminator
import zio.json.jsonHint
import zio.json.jsonMemberNames

sealed trait NcfId {
  def value:        String
  def validDscName: String
}
final case class BundleName(value: String) extends NcfId {
  val validDscName: String = value.split("_").map(_.capitalize).mkString("-")
}

final case class ParameterId(value: String) extends NcfId {
  val validDscName: String = value.split("_").map(_.capitalize).mkString("")
}

sealed trait ResourceFileState {
  def value: String
}

object ResourceFileState {
  case object New       extends ResourceFileState { val value = "new"       }
  case object Deleted   extends ResourceFileState { val value = "deleted"   }
  case object Modified  extends ResourceFileState { val value = "modified"  }
  case object Untouched extends ResourceFileState { val value = "untouched" }

  val allValues: List[ResourceFileState] = New :: Deleted :: Modified :: Untouched :: Nil

  def parse: String => PureResult[ResourceFileState] = { value =>
    allValues.find(_.value == value) match {
      case None        => Left(Unexpected(s"$value is not valid resource state value"))
      case Some(state) => Right(state)
    }
  }
}

// a resource file for technique. Be careful, sometime path is given relative to technique (should be always that)
// be sometime relative to a sub-directory named "resources"
case class ResourceFile(
    path:  String,
    state: ResourceFileState
)

final case class EditorTechnique(
    id:            BundleName,
    version:       Version,
    name:          String,
    category:      String,
    calls:         Seq[MethodElem],
    description:   String,
    documentation: String,
    parameters:    Seq[TechniqueParameter],
    resources:     Seq[ResourceFile],
    tags:          Map[String, Json],
    internalId:    Option[String]
) {
  val path: String = s"techniques/${category}/${id.value}/${version.value}"
}

object EditorTechnique {

  /*
   * Check for agreement between technique id from path and technique id from descriptor since the technique may
   * have been put in Rudder by hand by a dev (see https: //issues.rudder.io/issues/23474)
   */
  def checkTechniqueIdConsistency(techniqueBaseDirectory: File, techniqueDescriptor: EditorTechnique): IOResult[Unit] = {
    ZIO
      .when(!techniqueBaseDirectory.path.endsWith(techniqueDescriptor.path)) {
        ZIO.fail(
          Inconsistency(
            s"Technique descriptor at path '${techniqueBaseDirectory.pathAsString}' contains a technique 'id' or " +
            s"'version' attribute that does not match the conventional path of the technique " +
            s"which must be: '.../category/parts/.../{techniqueId}/{techniqueVersion}/technique.yml'. " +
            s"Please change either technique directory or the descriptor information so that they " +
            s"match one other each others."
          )
        )
      }
      .unit
  }
}

@jsonDiscriminator("type")
sealed trait MethodElem

@jsonHint("block")
final case class MethodBlock(
    id:             String,
    component:      String,
    reportingLogic: ReportingLogic,
    condition:      String,
    calls:          List[MethodElem],
    policyMode:     Option[PolicyMode],
    foreach:        Option[List[Map[String, String]]],
    foreachName:    Option[String]
) extends MethodElem

@jsonHint("call")
final case class MethodCall(
    method:            BundleName,
    id:                String,
    parameters:        Map[ParameterId, String],
    condition:         String,
    component:         String,
    disabledReporting: Boolean,
    policyMode:        Option[PolicyMode],
    foreach:           Option[List[Map[String, String]]],
    foreachName:       Option[String]
) extends MethodElem

object MethodCall {
  def renameParams(call: MethodCall, methods: Map[BundleName, GenericMethod]): MethodCall = {

    val renameParam = methods.get(call.method).map(_.renameParam).getOrElse(Nil).toMap
    val newParams   = call.parameters.map {
      case (parameterId: ParameterId, value) =>
        (renameParam.get(parameterId.value).map(ParameterId.apply).getOrElse(parameterId), value)
    }
    call.copy(parameters = newParams)
  }
}

final case class GenericMethod(
    id:             BundleName,
    name:           String,
    parameters:     Seq[MethodParameter],
    classParameter: ParameterId,
    classPrefix:    String,
    agentSupport:   Seq[AgentType],
    description:    String,
    documentation:  Option[String],
    deprecated:     Option[
      String
    ], // New name of the method replacing this method, may be defined only if deprecated is defined. Maybe we should have a deprecation info object

    renameTo:       Option[String],
    renameParam:    Seq[(String, String)]
)

final case class MethodParameter(
    id:            ParameterId,
    description:   String,
    constraint:    List[Constraint],
    parameterType: ParameterType.ParameterType
)
final case class TechniqueParameter(
    id:            ParameterId,
    name:          String,
    description:   Option[String],
    documentation: Option[String],
    mayBeEmpty:    Boolean,
    constraints:   Option[Constraints]
)

object ParameterType {
  trait ParameterType
  case object StringParameter extends ParameterType
  case object Raw             extends ParameterType
  case object HereString      extends ParameterType

  trait ParameterTypeService {
    def create(value:        String): PureResult[ParameterType]
    def value(parameterType: ParameterType): PureResult[String]
    def translate(value:     String, paramType: ParameterType, agentType: AgentType): PureResult[String]
  }

  class BasicParameterTypeService extends ParameterTypeService {
    def create(value: String): PureResult[ParameterType] = {
      value match {
        case "string"      => Right(StringParameter)
        case "here-string" => Right(HereString)
        case "raw"         => Right(Raw)
        // For compatibility with previous format
        case "HereString"  => Right(HereString)
        case _             => Left(Unexpected(s"'${value}' is not a valid method parameter type"))
      }
    }

    def value(parameterType: ParameterType):                                      PureResult[String] = {
      parameterType match {
        case StringParameter => Right("string")
        case Raw             => Right("raw")
        case HereString      => Right("HereString")
        case _               => Left(Unexpected(s"parameter type '${parameterType}' has no value defined"))
      }
    }
    def translate(value: String, paramType: ParameterType, agentType: AgentType): PureResult[String] = {
      (paramType, agentType) match {
        case (Raw, _)                                               => Right(value)
        case (StringParameter | HereString, AgentType.CfeCommunity) =>
          Right(s""""${value.replaceAll("""\\""", """\\\\""").replaceAll(""""""", """\\"""")}"""")
        case (HereString, AgentType.Dsc)                            =>
          translateDscHereString(value)
        case (StringParameter, AgentType.Dsc)                       =>
          Right(s""""${value.replaceAll("\"", "`\"")}"""")
        case (_, _)                                                 => Left(Unexpected("Cannot translate"))
      }
    }

    private def translateDscHereString(value: String): PureResult[String] = {
      // Format to DSC specific expression for a known Rudder path variable.
      def mkDatastate(path: List[String]) = s"""([Rudder.Datastate]::Render('{{' + @'
                                               |vars.${path.mkString(".")}
                                               |'@ + '}}'))""".stripMargin

      // Refuse "dots" in individual path keys for DSC. For now, assume we only have names without interpolation itself in a path element
      def validatedPath(path: List[String]): PureResult[Unit] = {
        Either.cond(
          !path.exists(_.contains('.')),
          (),
          Unexpected(
            s"Cannot use property in DSC agent, '.' is not supported in object keys for provided path ${path.mkString("->")}"
          )
        )
      }

      def translateToken(token: Token) = token match {
        case CharSeq(s) => Right(s"""@'
                                    |${s}
                                    |'@""".stripMargin)
        case v: NonRudderVar =>
          validatedPath(v.accessors).as(mkDatastate(List(v.fullPath)))
        case UnsafeRudderVar(s)              =>
          Left(
            Unexpected(
              s"Cannot use property '${s}' in DSC agent, the syntax is considered unsafe. Please consider using documented Rudder properties interpolations"
            )
          )
        case NodeAccessor(path)              =>
          validatedPath(path).as(mkDatastate("rudder" :: "node" :: path))
        case Param(path)                     =>
          validatedPath(path).as(
            mkDatastate("rudder" :: "param" :: path)
          ) // "param" : same as in InterpolatedValueCompilerImpl#translate
        case Property(
              path,
              _ // templating options can be ignored because template is already rendered statically with default value when necessary
            ) =>
          validatedPath(path).as(mkDatastate("node" :: "properties" :: path))
        case RudderEngine(engine, method, _) =>
          validatedPath(method).as(mkDatastate("data" :: engine :: method))
      }

      def assembleDscExpr(tokens: List[Token]) = tokens.accumulatePure(translateToken).map {
        case single :: Nil => single
        case o             => o.mkString("(", " + ", ")")
      }

      PropertyParser.parse(value).flatMap(assembleDscExpr)
    }
  }

  class PlugableParameterTypeService extends ParameterTypeService {

    def create(value: String):               PureResult[ParameterType] = {
      (innerServices foldRight (Left(Unexpected(s"'${value}' is not a valid method parameter type")): PureResult[
        ParameterType
      ])) {
        case (_, res @ Right(_)) => res
        case (service, _)        => service.create(value)
      }
    }
    def value(parameterType: ParameterType): PureResult[String]        = {
      (innerServices foldRight (Left(Unexpected(s"parameter type '${parameterType}' has no value defined")): PureResult[
        String
      ])) {
        case (_, res @ Right(_)) => res
        case (service, _)        => service.value(parameterType)
      }
    }

    def translate(value: String, paramType: ParameterType, agentType: AgentType): PureResult[String] = {
      (innerServices foldRight (Left(Unexpected(s"'${value}' is not a valid method parameter type")): PureResult[String])) {
        case (_, res @ Right(_)) => res
        case (service, _)        => service.translate(value, paramType, agentType)
      }
    }

    def addNewParameterService(service: ParameterTypeService): Unit = innerServices = service :: innerServices
    private var innerServices: List[ParameterTypeService] = new BasicParameterTypeService :: Nil
  }
}

case class SelectOption(value: String, name: Option[String])

@jsonMemberNames(SnakeCase)
case class Constraints(
    allowEmpty:      Option[Boolean],
    allowWhiteSpace: Option[Boolean],
    minLength:       Option[Int],
    maxLength:       Option[Int],
    regex:           Option[String],
    notRegex:        Option[String],
    select:          Option[List[SelectOption]]
)

object Constraint {
  sealed trait Constraint {
    def check(value: String): CheckResult
  }
  import NonEmptyList.one

  case class AllowEmpty(allow: Boolean) extends Constraint {
    def check(value: String): CheckResult = {
      if (allow || value.nonEmpty) {
        OK
      } else {
        NOK(one("Must not be empty"))
      }
    }
  }

  case class AllowWhiteSpace(allow: Boolean)  extends Constraint {
    def check(value: String): CheckResult = {
      if (allow || Pattern.compile("""^(?!\s).*(?<!\s)$""", Pattern.DOTALL).asPredicate().test(value)) {
        OK
      } else {
        NOK(one("Must not have leading or trailing whitespaces"))
      }
    }
  }
  case class MaxLength(max: Int)              extends Constraint {
    def check(value: String): CheckResult = {
      if (value.size <= max) {
        OK
      } else {
        val agentMaxNotice = {
          if (max == 16384)
            " Fields over 16384 characters are currently not supported. If you want to edit a file, please insert your content into a file, and copy it with a file_copy_* method, or use a template."
          else ""
        }
        NOK(one(s"Max length is ${max}. Current size is ${value.size}.${agentMaxNotice}"))
      }
    }
  }
  case class MinLength(min: Int)              extends Constraint {
    def check(value: String): CheckResult = {
      if (value.size >= min) {
        OK
      } else {
        NOK(one(s"Min length is ${min}. Current size is ${value.size}."))
      }
    }
  }
  case class MatchRegex(regex: String)        extends Constraint {
    def check(value: String): CheckResult = {
      if (value.matches(regex)) {
        OK
      } else {
        NOK(one(s"Must match regex '${regex}'"))
      }
    }
  }
  case class NotMatchRegex(regex: String)     extends Constraint {
    def check(value: String): CheckResult = {
      if (!value.matches(regex)) {
        OK
      } else {
        NOK(one(s"Must not match regex '${regex}'"))
      }
    }
  }
  case class FromList(list: List[String])     extends Constraint {
    def check(value: String): CheckResult = {
      if (list.contains(value)) {
        OK
      } else {
        NOK(one(s"Must be an accepted value: ${list.mkString(", ")}"))
      }
    }
  }
  sealed trait CheckResult
  case object OK                              extends CheckResult
  case class NOK(cause: NonEmptyList[String]) extends CheckResult
}

object CheckConstraint {
  def check(constraint: List[Constraint.Constraint], value: String): CheckResult = {
    import Constraint.*

    constraint.map(_.check(value)).foldRight(OK: CheckResult) {
      case (OK, OK)           => OK
      case (NOK(m1), NOK(m2)) => NOK(m1 ::: m2)
      case (res: NOK, _)      => res
      case (_, res: NOK)      => res
    }
  }
}
