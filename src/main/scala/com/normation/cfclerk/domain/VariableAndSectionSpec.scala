/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/

package com.normation.cfclerk.domain

import com.normation.cfclerk.exceptions._
import scala.xml._
import net.liftweb.common._
import com.normation.cfclerk.xmlparsers.CfclerkXmlConstants._
import com.normation.utils.HashcodeCaching

/**
 * This file define the model for metadata of object 
 * contained in SECTIONS. 
 * A section may contains other section or variables. 
 */


/**
 * Generic trait for object in a section. 
 */
sealed trait SectionChildSpec {
  def name: String

  def getVariables: Seq[VariableSpec] = this match {
    case variable: SectionVariableSpec => Seq(variable)
    case section: SectionSpec => section.children.flatMap { child =>
      child match {
        case v: VariableSpec => Seq(v)
        case _ => Seq()
      }
    }
  }

  def getAllSections: Seq[SectionSpec] = this match {
    case v:SectionVariableSpec => Seq()
    case s:SectionSpec => s +: s.children.flatMap( _.getAllSections )
  }
  
  // get current variables and variables in sub section
  def getAllVariables: Seq[VariableSpec] = this match {
    case variable: SectionVariableSpec => Seq(variable)
    case section: SectionSpec =>
      section.children.flatMap(_.getAllVariables)
  }

  def filterByName(name: String): Seq[SectionChildSpec] = {
    val root = if (this.name == name) Seq(this) else Seq()

    val others = this match {
      case section: SectionSpec =>
        section.children.flatMap(_.filterByName(name))
      case variable: SectionVariableSpec => Seq()
    }
    root ++ others
  }
}


/**
 * Metadata about a section object. 
 */
case class SectionSpec(
    name         : String
  , isMultivalued: Boolean = false
  , isComponent  : Boolean = false
  , componentKey : Option[String] = None
  , foldable     : Boolean = false
  , description  : String = ""
  , children     : Seq[SectionChildSpec] = Seq()
) extends SectionChildSpec with HashcodeCaching {

  lazy val getDirectVariables : Seq[VariableSpec] = {
    children.collect { case v:VariableSpec => v }
  }
  
  lazy val getDirectSections : Seq[SectionSpec] = {
    children.collect { case s:SectionSpec => s }
  }
  
  def copyWithoutSystemVars: SectionSpec =
    filterChildren {
      case variable: VariableSpec => !variable.isSystem
      case _ => true
    }

  // do recursively a filter on each SectionChild
  def filterChildren(f: SectionChildSpec => Boolean): SectionSpec = {
    val kept = children.filter(f) map { child =>
      child match {
        case secSpec: SectionSpec => secSpec.filterChildren(f)
        case varSpec: SectionVariableSpec => varSpec
      }
    }
    this.copy(children = kept)
  }

  def cloneVariablesInMultivalued: SectionSpec = {
    assert(isMultivalued)

    recCloneMultivalued
  }

  private def recCloneMultivalued: SectionSpec = {
    val multivaluedChildren = for (child <- children) yield child match {
      case s: SectionSpec =>
        if (s.isMultivalued) throw new TechniqueException(
          "A multivalued section should not contain other multivalued sections." +
            " It may contain only imbricated sections or variables.")
        else
          s.recCloneMultivalued
      case v: SectionVariableSpec => v.cloneSetMultivalued
    }

    copy(children = multivaluedChildren)
  }
}

object SectionSpec {
  def isSection(sectionName: String) = sectionName == "SECTION"
}

/**
 * Metadata about a Variable: name, description, type, etc
 * but no mutable data (no values)
 *
 */
sealed trait VariableSpec {
  type T <: VariableSpec
  type V <: Variable //type of variable linked to that variable spec

  def name: String
  def description: String
  def longDescription: String

  // a uniqueVariable has the same values over each policies
  def isUniqueVariable: Boolean
  def multivalued: Boolean

  // if true, check that the value set match the type  
  // Some value shouldn't be checked : when we set their value, we don't check anything
  def checked: Boolean

  //create a new variable from that spec
  def toVariable(values: Seq[String] = Seq()): V

  /*
   * children classes have to override that method
   * which make a clone of the spec, setting the
   * cloned to multivalued = true.
   * It's needed to handle multi instance in TemplateDependencies
   */
  def cloneSetMultivalued: T

  // it is a system variable only if the class extending this trait is 
  //  a SystemVariableSpec or a TrackerVariableSpec
  def isSystem: Boolean = {
    this match {
      case _: SystemVariableSpec | _: TrackerVariableSpec => true
      case _ => false
    }
  }

  def constraint: Constraint
}

case class SystemVariableSpec(
  override val name: String,
  val description: String,
  val longDescription: String = "",
  val valueslabels: Seq[ValueLabel] = Seq(),
  // a uniqueVariable has the same values over each policies
  val isUniqueVariable: Boolean = false,
  val multivalued: Boolean = false,

  // we expect that by default the variable will be checked
  val checked: Boolean = true,

  val constraint: Constraint = Constraint()
  
) extends VariableSpec with HashcodeCaching {

  override type T = SystemVariableSpec
  override type V = SystemVariable
  override def cloneSetMultivalued: SystemVariableSpec = this.copy(multivalued = true)
  def toVariable(values: Seq[String] = Seq()): SystemVariable = SystemVariable(this, values)
}


case class TrackerVariableSpec(
  val boundingVariable: Option[String] = None
) extends VariableSpec with HashcodeCaching {

  override type T = TrackerVariableSpec
  override type V = TrackerVariable

  override val name: String = TRACKINGKEY
  override val description: String = "Variable which kept information about the policy"

  override  val isUniqueVariable: Boolean = false

  override val checked: Boolean = false

  val constraint: Constraint = Constraint()
 
  override val multivalued = true
  override val longDescription = ""
  override def cloneSetMultivalued: TrackerVariableSpec = this.copy()
  def toVariable(values: Seq[String] = Seq()): TrackerVariable = TrackerVariable(this, values)
}

/**
 * Here we have all the variable that can be declared in sections
 * (all but system vars). 
 */
sealed trait SectionVariableSpec extends SectionChildSpec with VariableSpec {
  override type T <: SectionVariableSpec
}

case class ValueLabel(value: String, label: String) extends HashcodeCaching  {
  def tuple = (value, label)
  def reverse = ValueLabel(label, value)
}

trait ValueLabelVariableSpec extends SectionVariableSpec {
  val valueslabels: Seq[ValueLabel]
}

case class SelectVariableSpec(
  override val name: String,
  val description: String,
  val longDescription: String = "",
  val valueslabels: Seq[ValueLabel] = Seq(),
  // a uniqueVariable has the same values over each policies
  val isUniqueVariable: Boolean = false,
  val multivalued: Boolean = false,

  // we expect that by default the variable will be checked
  val checked: Boolean = true,

  val constraint: Constraint = Constraint()
  
) extends ValueLabelVariableSpec with HashcodeCaching {

  override type T = SelectVariableSpec
  override type V = SelectVariable
  override def cloneSetMultivalued: SelectVariableSpec = this.copy(multivalued = true)
  def toVariable(values: Seq[String] = Seq()): SelectVariable = SelectVariable(this, values)
}

case class SelectOneVariableSpec(
  override val name: String,
  val description: String,
  val longDescription: String = "",
  val valueslabels: Seq[ValueLabel] = Seq(),
  // a uniqueVariable has the same values over each policies
  val isUniqueVariable: Boolean = false,
  val multivalued: Boolean = false,

  // we expect that by default the variable will be checked
  val checked: Boolean = true,

  val constraint: Constraint = Constraint()

) extends ValueLabelVariableSpec with HashcodeCaching {

  override type T = SelectOneVariableSpec
  override type V = SelectOneVariable
  override def cloneSetMultivalued: SelectOneVariableSpec = this.copy(multivalued = true)
  def toVariable(values: Seq[String] = Seq()): SelectOneVariable = SelectOneVariable(this, values)
}

case class InputVariableSpec(
  override val name: String,
  val description: String,
  val longDescription: String = "",
  // a uniqueVariable has the same values over each policies
  val isUniqueVariable: Boolean = false,
  val multivalued: Boolean = false,

  // we expect that by default the variable will be checked
  val checked: Boolean = true,

  val constraint: Constraint = Constraint()

) extends SectionVariableSpec with HashcodeCaching {

  override type T = InputVariableSpec
  override type V = InputVariable
  override def cloneSetMultivalued: InputVariableSpec = this.copy(multivalued = true)
  def toVariable(values: Seq[String] = Seq()): InputVariable = InputVariable(this, values)
}


/**
 * This object is the central parser for VariableSpec, so
 * it has to know all possible VariableSpec type.
 * The pattern matching means that it won't be easily extended.
 * A more plugable architecture (partial function, pipeline...)
 * will have to be set-up to achieve such a goal.
 */
object SectionVariableSpec {  
  def markerNames = List(INPUT, SELECT1, SELECT)

  def isVariable(variableName: String) = markerNames contains variableName

  /**
   * Default variable implementation
   * Some of the arguments are not used by all implementations of Variable.
   * For instance, boundingVariable is only used for policyinstance variable.
   */
  def apply(
    varName: String,
    description: String,
    markerName: String,
    longDescription: String = "",
    valueslabels: Seq[ValueLabel],
    // a uniqueVariable has the same values over each policies
    isUniqueVariable: Boolean = false,
    multivalued: Boolean = false,
    checked: Boolean = true,
    constraint: Constraint = Constraint()): SectionVariableSpec = {

    markerName match {
      case INPUT => InputVariableSpec(varName, description, longDescription,
        isUniqueVariable, multivalued, checked, constraint)
      case SELECT => SelectVariableSpec(varName, description, longDescription,
        valueslabels, isUniqueVariable, multivalued, checked, constraint)
      case SELECT1 => SelectOneVariableSpec(varName, description, longDescription,
        valueslabels, isUniqueVariable, multivalued, checked, constraint)
      case x => throw new IllegalArgumentException("Unknown variable kind: " + x)
    }
  }
}

