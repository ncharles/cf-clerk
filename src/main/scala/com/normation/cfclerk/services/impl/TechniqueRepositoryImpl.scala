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

package com.normation.cfclerk.services.impl

import com.normation.cfclerk.services._
import com.normation.cfclerk.domain._
import org.slf4j.{ Logger, LoggerFactory }
import net.liftweb.common._
import Box._
import com.normation.cfclerk.exceptions._
import java.io.{ File, InputStream }
import scala.collection.SortedSet
import scala.collection.mutable
import com.normation.eventlog.EventActor

class TechniqueRepositoryImpl(
    techniqueReader: TechniqueReader
  , refLibCallbacks: Seq[TechniquesLibraryUpdateNotification]
) extends TechniqueRepository with UpdateTechniqueLibrary with Loggable {

  /**
   * Callback to call on PTLib update
   */
  private[this] val callbacks = scala.collection.mutable.Buffer(refLibCallbacks:_*)

  
  /*
   * TechniquesInfo:
   * - techniquesCategory: Map[TechniqueId, TechniqueCategoryId]
   * - techniques: Map[TechniqueName, SortedMap[TechniqueVersion, Technique]] 
   * - categories: SortedMap[TechniqueCategoryId, TechniqueCategory]
   */
  private[this] var techniqueInfosCache: TechniquesInfo = {
    /*
     * readTechniques result is updated only on 
     * techniqueReader.getModifiedTechniques,
     * so we don't call that method at boot time. 
     */
    try {
      techniqueReader.readTechniques
    } catch {
      case e:Exception => 
        val msg = "Error when loading the previously saved policy template library. Trying to update to last library available to overcome the error"
        logger.error(msg)
        this.update(CfclerkEventActor, Some(msg))
        techniqueReader.readTechniques
    }
  }
    
  ////// end constructor /////
  
  
  /**
   * Register a new callback
   */
  override def registerCallback(callback:TechniquesLibraryUpdateNotification) : Unit = {
    callbacks.append(callback)
  }
  
  override def update(actor:EventActor, reason: Option[String]) : Box[Seq[TechniqueId]] = {
    try {
      val modifiedPackages = techniqueReader.getModifiedTechniques
      if (modifiedPackages.nonEmpty || /* first time init */ null == techniqueInfosCache) {
        logger.info("Reloading technique library, " + {
          if (modifiedPackages.isEmpty) "no modified techniques found"
          else "found modified technique(s): " + modifiedPackages.mkString(", ")
        })
        techniqueInfosCache = techniqueReader.readTechniques
  
        callbacks.foreach { callback =>
          try {
            callback.updatedTechniques(modifiedPackages, actor, reason)
          } catch {
            case e: Exception => logger.error("Error when executing callback '%s' with updated technique: '%s'".format(callback.name, modifiedPackages.mkString(", ")), e)
          }
        }
  
      } else {
        logger.debug("Not reloading technique library as nothing changed since last reload")
      }
      Full(modifiedPackages)
    } catch {
      case e:Exception => Failure("Error when trying to read technique library", Full(e), Empty)
    }
  }
  



  override def getTemplateContent(templateName: Cf3PromisesFileTemplateId)(useIt: Option[InputStream] => Unit): Unit =
    techniqueReader.getTemplateContent(templateName)(useIt)

  /**
   * Return all the policies available
   * @return
   */
  override def getAll: Map[TechniqueId, Technique] = {
    (for {
      (id, versions) <- techniqueInfosCache.techniques
      (v, p) <- versions
    } yield {
      (TechniqueId(id, v), p)
    }).toMap
  }

  override def getTechniqueVersions(name: TechniqueName): SortedSet[TechniqueVersion] = {
    SortedSet[TechniqueVersion]() ++ techniqueInfosCache.techniques.get(name).toSeq.flatMap(_.keySet)
  }

  /**
   * Retrieve the list of policies corresponding to the ids
   * @param techniqueIds : identifiers of the policies
   * @return : the list of policy objects
   * Throws an error if one policy ID does not match any known policy
   */
  override def getByIds(techniqueIds: Seq[TechniqueId]): Seq[Technique] = {
    techniqueIds.map(x => techniqueInfosCache.techniques(x.name)(x.version))
  }

  /**
   * Return a policy by its name
   * @param policyName
   * @return
   */
  override def get(techniqueId: TechniqueId): Option[Technique] = {
    val result = techniqueInfosCache.techniques.get(techniqueId.name).flatMap(versions => versions.get(techniqueId.version))
    if(!result.isDefined) {
      logger.debug("Required technique '%s' was not found".format(techniqueId))
    }
    result
  }

  override def getLastTechniqueByName(policyName: TechniqueName): Option[Technique] = {
    techniqueInfosCache.techniques.get(policyName).map { versions => versions.last._2 }
  }

  //////////////////////////////////// categories /////////////////////////////

  private def fileBreadCrump(target: File, current: File, stack: List[File]): List[File] = {

    if (current.getParentFile == target) target :: stack
    else fileBreadCrump(target, current.getParentFile, current :: stack)

  }

  override def getTechniqueLibrary: RootTechniqueCategory = techniqueInfosCache.rootCategory

  override def getTechniqueCategory(id: TechniqueCategoryId): Box[TechniqueCategory] = {
    id match {
      case RootTechniqueCategoryId => Full(this.techniqueInfosCache.rootCategory)
      case sid: SubTechniqueCategoryId => this.techniqueInfosCache.subCategories.get(sid)
    }
  }

  override def getParentTechniqueCategory_forTechnique(id: TechniqueId): Box[TechniqueCategory] = {
    for {
      cid <- this.techniqueInfosCache.techniquesCategory.get(id)
      cat <- cid match {
        case RootTechniqueCategoryId => Some(this.techniqueInfosCache.rootCategory)
        case sid: SubTechniqueCategoryId => this.techniqueInfosCache.subCategories.get(sid)
      }
    } yield {
      cat
    }
  }
}
