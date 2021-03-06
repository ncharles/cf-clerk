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

import java.io.FileNotFoundException
import org.junit._
import org.junit.runner._
import org.specs2.mutable._
import org.specs2.runner._
import org.xml.sax.SAXParseException
import scala.xml._
import com.normation.cfclerk.xmlparsers._
import CfclerkXmlConstants._
import scala.xml._
import net.liftweb.common._
import com.normation.utils.XmlUtils._
import org.joda.time.{ Days => _, _ }
import org.joda.time.format._
import org.slf4j.{ Logger, LoggerFactory }
import com.normation.cfclerk.exceptions.ParsingException
import com.normation.utils.HashcodeCaching

@RunWith(classOf[JUnitRunner])
class SectionTest extends Specification {
  val doc = readFile("testSections.xml")
  def sectionsTag(example:String) = (doc \\ "examples" \ example \ "SECTIONS").head
  
  val sectionSpecParser = new SectionSpecParser(new VariableSpecParser)

  val sectionParser = SectionParser(sectionSpecParser)
  
  
  "Example <variableUnderSections>" should {
    "leads to a parsing exception due to variable under <SECTIONS> tag" in {
      sectionParser.parseXml(sectionsTag("variableUnderSections")) must throwA[ParsingException].like { case e => 
        e.getMessage must =~("<%s> must contain only <%s> children".format(SECTIONS_ROOT, SECTION))
      }
    }
  }  
  
  "Example <nonUniqueSectionNames>" should {
    "leads to a parsing exception due to multiple section with the same name" in {
      sectionParser.parseXml(sectionsTag("nonUniqueSectionNames")) must throwA[ParsingException].like { case e => 
        e.getMessage must =~("At least two sections have the same name")
      }
    }
  }
  
  "Example <nonUniqueVariableNames>" should {
    "leads to a parsing exception due to multiple variables with the same name" in {
      sectionParser.parseXml(sectionsTag("nonUniqueVariableNames")) must throwA[ParsingException].like { case e => 
        e.getMessage must =~("At least two variables have the same name")
      }
    }
  }  
  
  
  ///// test on the well formed example /////
  
  
  val rootSectionsOk = sectionParser.parseXml(sectionsTag("ok"))
    
  "the test sections <ok>" should {
    val vars = "A" :: "B" :: "C" :: "D" :: "E" :: "F" :: Nil 
    val sects = SECTION_ROOT_NAME :: "sectA" :: "sect1" :: "sect2" :: 
      "sect3" :: "emptySect" :: "component" :: "sectF" ::Nil
    
    "have variables %s".format(vars.mkString("[", "," , "]")) in {
      vars === rootSectionsOk.getAllVariables.map( _.name )
    }
    
    "have sections %s".format(sects.mkString("[", "," , "]")) in {
      sects === rootSectionsOk.getAllSections.map( _.name )
    }
    
  }

  "section named sect1" should {
    implicit val section = getUniqueSection("sect1")
    containNbVariables(4)
    beMultivalued
  }

  "section named sect2" should {
    implicit val section = getUniqueSection("sect2")
    haveNbChildren(2) 
    //all children are variables
    containNbVariables(2)
  }

  "section named sect3" should {
    implicit val section = getUniqueSection("sect3")
    haveNbChildren(1) 
    //all children are variables
    containNbVariables(1)
  }

  "section named emptySect" should {
    implicit val section = getUniqueSection("emptySect")
    beEmpty
    haveNbChildren(0)
  }
  
  "section named component" should {
    implicit val section = getUniqueSection("component")
    beEmpty
    haveNbChildren(0)
    
    "be a component" in {
      section.isComponent must beTrue
    }
    
    "have component key C" in {
      section.componentKey === Some("C")
    }
  }
  
  private[this] def getUniqueSection(name: String): SectionSpec = {
    implicit val sects = rootSectionsOk.filterByName(name)
    beUnique

    implicit val sectChild = sects.head
    beSection

    sectChild match {
      case sect: SectionSpec => sect
      case _ => sys.error("Have not a section should not happen")
    }
  }

  private[this] def beEmpty(implicit section: SectionSpec) = {
    "be empty" in {
      section.children.size mustEqual 0
    }
  }

  private[this] def haveNbChildren(nbChildren: Int)(implicit section: SectionSpec) = {
    "have %d children".format(nbChildren) in {
      section.children.size mustEqual nbChildren
    }
  }

  private[this] def containNbVariables(nbVariables: Int)(implicit section: SectionSpec) = {
    "contain %d variables".format(nbVariables) in {
      section.getAllVariables.size mustEqual nbVariables
    }
  }

  private[this] def beUnique(implicit children: Seq[SectionChildSpec]) = {
    "be unique" in {
      children.size mustEqual 1
    }
  }

  private[this] def beVariable(implicit sectionChild: SectionChildSpec) = {
    "be a variable" in {
      sectionChild.isInstanceOf[VariableSpec]
    }
  }

  private[this] def beSection(implicit sectionChild: SectionChildSpec) = {
    "be a section" in {
      sectionChild.isInstanceOf[SectionSpec]
    }
  }

  private[this] def beMultivalued(implicit section: SectionSpec) = {
    "be multivalued" in {
      section.isMultivalued
    }
  }

  
  private[this] def readFile(fileName:String) : Elem = {
    val doc =
      try {
        XML.load(ClassLoader.getSystemResourceAsStream(fileName))
      } catch {
        case e: SAXParseException => throw new Exception("Unexpected issue (unvalid xml?) with " + fileName)
        case e: java.net.MalformedURLException => throw new FileNotFoundException("%s file not found".format(fileName))
      }
    if (doc.isEmpty) {
      throw new Exception("Unexpected issue (unvalid xml?) with the %s file".format(fileName))
    }
    doc
  }
  
}

case class SectionParser(sectionSpecParser: SectionSpecParser) extends HashcodeCaching  {
  val id = new TechniqueId(new TechniqueName("test-TechniqueId"), TechniqueVersion("1.0"))
  val policyName = "test-policyName"

  def parseXml(elt: Node): SectionSpec = {
    sectionSpecParser.parseSectionsInPolicy(elt,id, policyName) 
  }
}
