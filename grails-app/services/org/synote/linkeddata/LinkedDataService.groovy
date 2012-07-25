package org.synote.linkeddata

import grails.converters.*

import org.codehaus.groovy.grails.web.context.ServletContextHolder as SCH
import org.springframework.web.context.request.RequestContextHolder as RCH
import org.synote.linkeddata.RedirectData
import org.synote.user.User
import org.synote.resource.Resource
import org.synote.resource.single.text.MultimediaTag
import org.synote.resource.single.text.MultimediaTextNote
import org.synote.resource.compound.MultimediaResource
import org.synote.resource.single.text.SynmarkTag
import org.synote.resource.single.text.SynmarkTextNote
import org.synote.resource.compound.SynmarkResource
import org.synote.resource.compound.PresentationResource
import org.synote.resource.compound.WebVTTResource
import org.synote.resource.single.binary.PresentationSlide
import org.synote.annotation.ResourceAnnotation
import org.synote.annotation.Annotation
import org.synote.resource.single.text.WebVTTCue
import org.synote.annotation.synpoint.Synpoint
import org.synote.linkeddata.exception.RDFGenerationException

import org.synote.config.ConfigurationService
import org.synote.permission.PermService
import org.synote.utils.UtilsService
import org.synote.user.SecurityService
import org.synote.linkeddata.Vocabularies as V
import org.synote.linkeddata.RDFBuilder
import org.synote.linkeddata.exception.RDFGenerationException
import com.hp.hpl.jena.datatypes.xsd.XSDDateTime
import com.hp.hpl.jena.datatypes.xsd.XSDDatatype

import org.synote.player.client.TimeFormat

import net.rootdev.javardfa.jena.RDFaReader
import com.hp.hpl.jena.rdf.model.Model
import com.hp.hpl.jena.rdf.model.ModelFactory
import com.hp.hpl.jena.rdf.model.Resource as JenaResource //type clashed with synote Resource
import com.hp.hpl.jena.rdf.model.Property as JenaProperty
import com.hp.hpl.jena.rdf.model.Literal
import com.hp.hpl.jena.rdf.model.AnonId //for the id of blank node
import com.hp.hpl.jena.util.FileManager
import com.hp.hpl.jena.sparql.util.Utils
import com.hp.hpl.jena.sdb.SDBFactory
import com.hp.hpl.jena.sdb.store.DatasetStore
import com.hp.hpl.jena.graph.GraphEvents
import com.hp.hpl.jena.update.GraphStore
import com.hp.hpl.jena.update.GraphStoreFactory
import com.hp.hpl.jena.update.UpdateAction
import com.hp.hpl.jena.update.UpdateRequest
import com.hp.hpl.jena.update.UpdateFactory
import com.hp.hpl.jena.query.*
import org.w3c.tidy.Tidy

import java.util.Calendar
import java.util.GregorianCalendar
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream

//For nerd
import fr.eurecom.nerd.client.schema.Extraction

class LinkedDataService {

    static transactional = true
	def configurationService
	def permService
	def utilsService
	def securityService
	def grailsApplication
	
	def tripleStore
	
	/*
	 * Get the triple store
	 */
	def getSDBInstance()
	{
		if(grailsApplication.config.jena.enabled != true)
			return null
		
		if(!tripleStore)
		{
			String assembler = grailsApplication.config.jena.sdb.assembler
			log.debug("assembler:"+assembler)
			tripleStore = SDBFactory.connectStore(assembler)
			return tripleStore
		}
		else
			return tripleStore
	}
    def getBaseURI() {
		return configurationService.getConfigValue("org.synote.linkeddata.settings.baseURI")+
			configurationService.getConfigValue("org.synote.linkeddata.settings.serverContext")
    }
	/*
	* Get the base uris for the ids, the id is not included
	*/
	def getResourceBaseURI()
	{
		getBaseURI()+"resources/"
	}
	
	def getAnnotationBaseURI()
	{
		getBaseURI()+"annotations/"
	}
	
	def getNERDAnnotationBaseURI()
	{
		getBaseURI()+"annotations/nerd/"	
	}
	
	def getUserBaseURI()
	{
		getBaseURI()+"users/"
	}
	
	def getHTMLBaseURI()
	{
		getBaseURI()+"recording/replay/"	
	}
	
	def getDefaultThumbnailImageURI()
	{
		getBaseURI()+"images/default.png"
	}
	
	/*
	 * Get the base uris for the rdf data, the id is not included
	 */
	def getResourceDataBaseURI()
	{
		getBaseURI()+"resources/data/"
	}
	
	def getAnnotationDataBaseURI()
	{
		getBaseURI()+"annotations/data/"
	}
	
	def getNERDAnnotationDataBaseURI()
	{
		getBaseURI()+"annotations/nerd/data/"
	}
	
	def getUserDataBaseURI()
	{
		getBaseURI()+"users/data/"
	}
	
	/*
	 * get the data necessary for redirection from the requested resource
	 * if the return is null, it means there is something wrong with the resource, e.g. you are not entitled to view it
	 * 
	 * If later new resources are added, we need to update this programme
	 */
	def getRedirectDataFromResource(Resource resource)
	{
		def recording =  null
		def annotation = null
		String frag = ""
		def compound_resource = null
		def synpoint = null
		
		if(resource.instanceOf(MultimediaResource))
		{
			recording = resource
		}
		else if(resource.instanceOf(MultimediaTextNote)|| resource.instanceOf(MultimediaTag))
		{
			recording = resource.multimedia
		}
		else
		{
			//resource is presentation or webvtt resource, go to the multimedia directly, with no fragment
			if(resource.instanceOf(PresentationResource) || resource.instanceOf(WebVTTResource))
			{
				compound_resource = resource
			}
			else if(resource.instanceOf(SynmarkResource))
			{
				compound_resource = resource
				
			}
			else if(resource.instanceOf(WebVTTCue))
			{
				compound_resource = resource.webVTTFile
			}
			else if(resource.instanceOf(PresentationSlide))
			{
				compound_resource = resource.presentation
			}
			else if(resource.instanceOf(SynmarkTag)|| resource.instanceOf(SynmarkTextNote))
			{
				compound_resource = resource.synmark
			}
			
			else
			{
				return null
			}
			
			if(compound_resource != null)
				annotation = ResourceAnnotation.findBySource(compound_resource)
			if(annotation)
			{
				recording = annotation.target
				def perm = permService.getPerm(recording)
				if(perm?.val <=0)
				{
					return null
				}
				
				if(resource.instanceOf(WebVTTCue))
				{
					//println resource.cueIndex
					synpoint = annotation.synpoints.find{it.sourceStart == resource.cueIndex}
				}
				else if(resource.instanceOf(PresentationSlide))
				{
					synpoint = annotation.synpoints.find{it.sourceStart == resource.index}
				}
				else if(resource.instanceOf(SynmarkTag)|| resource.instanceOf(SynmarkTextNote) || resource.instanceOf(SynmarkResource))
				{
					synpoint = annotation.synpoints?.toArray()[0]
				}
			}
			else
				return null
			
		}
		frag = getFragmentStringFromSynpoint(synpoint)
		
		return new RedirectData(recording,compound_resource,annotation,synpoint,frag)
	}
	
	/*
	* get the data necessary for redirection from the requested annotation
	* if the return is null, it means there is something wrong with the annotation, e.g. you are not entitled to view it
	*/
	def getRedirectDataFromAnnotation(ResourceAnnotation annotation)
	{
		def recording =  null
		String frag = ""
		def compound_resource = null
		def synpoint = null
		if(annotation)
		{
			recording =  annotation.target
			compound_resource = annotation.source
			def perm = permService.getPerm(recording)
			if(perm?.val <=0)
			{
				return null
			}
			
			if(compound_resource.instanceOf(SynmarkResource))
			{
				synpoint = annotation.synpoints?.toArray()[0]
			}
			
			if(synpoint != null)
			{
				frag = "t="+TimeFormat.getInstance().toWebVTTTimeString(synpoint.targetStart)
				if(synpoint.targetEnd)
				{
					frag+=","+TimeFormat.getInstance().toWebVTTTimeString(synpoint.targetEnd)
				}
			}
			
			return new RedirectData(recording,compound_resource,annotation,synpoint,frag)
		}
		else
			return null
		
		//return getRedirectDataFromResource(annotation.source)
	}
	
	/*
	 * Get fragmentString from Synpoint
	 */
	def getFragmentStringFromSynpoint(Synpoint synpoint)
	{
		def end = 0
		//targetEnd could be null
		if(synpoint?.targetEnd != null)
		{
			end = synpoint?.targetEnd
		}
		return getFragmentString(synpoint?.targetStart, end)
	}
	
	/*
	 * get media fragment string from start and end
	 */
	def getFragmentString(int start, int end)
	{
		String frag=""
		
		if(start)
		{
			frag = "t="+TimeFormat.getInstance().toWebVTTTimeString(start)
		}
		else
			frag="t=0"
		
		if(end)
		{
			frag+=","+TimeFormat.getInstance().toWebVTTTimeString(end)
		}
		return frag
	}
	
	/*
	 * The the html representation uri from the redirected data
	 */
	def getHTMLRepresentationURI(RedirectData rData)
	{
		//def g= new org.codehaus.groovy.grails.plugins.web.taglib.ApplicationTagLib()
		
		def htmlURI = getHTMLBaseURI()+rData.recording?.id
		if(rData.hasFragment())
		{
			//println "frag:"+redirectData.frag?.trim()
			htmlURI = utilsService.attachFragmentToURI(htmlURI,rData.getFragment())
		}
		
		return htmlURI
	}
	
	/*
	 * The the resource rdf representation uri from the redirected data
	 */
	def getResourceRDFRepresentationURI(RedirectData rData)
	{
		
		def dataURI = getResourceDataBaseURI()+rData.recording?.id
		if(rData.hasFragment())
		{
			//println "frag:"+redirectData.frag?.trim()
			dataURI = utilsService.attachFragmentToURI(dataURI,rData.getFragment())
		}
		
		return dataURI
	}
	
	/*
	 * parse RDFa in the xhtml string
	 * MUST catch the exception when use
	 */
	def parseRDFa(String xhtmlStr, Model model, String baseURI)
	{
		if(xhtmlStr!=null && model!=null && baseURI != null)
		{
			Class.forName("net.rootdev.javardfa.jena.RDFaReader")
			InputStream is = new ByteArrayInputStream(xhtmlStr.getBytes("UTF-8"))
			Tidy tidy = new Tidy()
			tidy.setShowErrors(0)
			tidy.setShowWarnings(false)
			tidy.setHideComments(true)
			tidy.setQuiet(true)
			tidy.setXHTML(true)
			ByteArrayOutputStream baos = new ByteArrayOutputStream()
			tidy.parse(is,baos)
			InputStream tidy_xhtml = new ByteArrayInputStream(baos.toByteArray())
			try
			{
				model.read(tidy_xhtml,baseURI,"XHTML")
			}
			catch(Exception ex)
			{
				log.debug("The note is not a valid xhtml:"+xhtmlStr)
			}
		}
	}
	
	/*
	 * The reusable method to build resource data
	 * params: output stream
	 */
	def buildResourceData(outputStream,resource) throws RDFGenerationException
	{
		if(!outputStream)
		{
			throw new RDFGenerationException("Missing output stream")
			return
		}
		else if(!resource)
		{
			throw new RDFGenerationException("Cannot find resource")
			return
		}
		else
		{
			RedirectData redirectData = getRedirectDataFromResource(resource)
			if(!redirectData)
			{
				throw new RDFGenerationException("Cannot get RDF from the resource. 3 possibilities: (1) the resource is not published as linked data yet. (2) You are note permitted to see it (3) it is not related to any annotation")
				return
			}
			else
			{
				String resURI = getResourceBaseURI()+resource.id
				def rdfBuilder = new RDFBuilder(outputStream)
				
				rdfBuilder.model.setNsPrefix("ma",V.MAONT_NS[1])
				rdfBuilder.model.setNsPrefix("dc",V.DC_NS[1])
				rdfBuilder.model.setNsPrefix("rdfs",V.RDFS_NS[1])
				rdfBuilder.model.setNsPrefix("dcterms",V.DCTERMS_NS[1])
				rdfBuilder.model.setNsPrefix("ore",V.ORE_NS[1])
				rdfBuilder.model.setNsPrefix("schemaorg",V.SCHEMAORG_NS[1])
				rdfBuilder.model.setNsPrefix("oac",V.OAC_NS[1])
				
				//rdfBuilder.model.setNsPrefix("dbpedia","http://dbpedia.org/resource/")
				//rdfBuilder.model.setNsPrefix("lode","http://linkedevents.org/ontology/")
				def output = null
				//println "class:"+resource.class.name
				//Now start to build rdf based on different resource classes
				output = rdfBuilder.xml{
					defaultNamespace resURI
					namespace rdf:V.RDF_NS[1]
					namespace rdfs:V.RDFS_NS[1]
					namespace dc:V.DC_NS[1]
					namespace dcterms:V.DCTERMS_NS[1]
					namespace ma:V.MAONT_NS[1]
					namespace ore:V.ORE_NS[1]
					namespace schemaorg:V.SCHEMAORG_NS[1]
					namespace oac:V.OAC_NS[1]
					//namespace dbpedia:"http://dbpedia.org/resource/"
					//namespace lode:"http://linkedevents.org/ontology/"
					subject(""){
						//Some common properties
						if(resource.title)
						{
							property "dc:title":resource.title
						}
						//dcterms creator is better, it is not the creator of the multimedia resource it self, he is just the uploader
						predicate "dcterms:creator":rdfBuilder.model.createResource(getUserBaseURI()+resource.owner?.id)
						def cal = Calendar.getInstance()
						cal.setTimeInMillis(resource.dateCreated.getTime())
						predicate "schemaorg:dateCreated":new XSDDateTime(cal)
						cal.setTimeInMillis(resource.lastUpdated.getTime())
						predicate "schemaorg:dateModified":new XSDDateTime(cal)
						subject("")
						{
							predicate("rdfs:isDefinedBy"){
								subject(getResourceDataBaseURI()+resource.id){
									property "dc:format":"application/rdf+xml"
								}
							}
						}
						subject("")
						{
							predicate ("rdfs:seeAlso"){
								subject(getHTMLRepresentationURI(redirectData)){
									property "dc:format":"application/xhtml+xml"
								}
							}
						}
						switch(resource.class.name) //class specific properties
						{
							case "org.synote.resource.compound.MultimediaResource":
								predicate "rdf:type":rdfBuilder.model.createResource(V.MAONT_NS[1]+"MediaResource")
								predicate "rdf:type":rdfBuilder.model.createResource(V.OAC_NS[1]+"Target")
								property "rdfs:label":resource.title
								property "ma:locator":resource.url?.url
								//hasFragment
								def annotations = ResourceAnnotation.findAllByTarget(resource)
								for(a in annotations)
								{
									def sourceResource = a.source
									def syns = Synpoint.findAllByAnnotation(a)
									for(syn in syns)
									{
										//a.synpoints.each{syn->
										subject(""){
											predicate("ma:hasFragment"){
												String f = getFragmentStringFromSynpoint(syn)
												String mfURIStr = utilsService.attachFragmentToURI(resURI,f)
												subject(mfURIStr){
													predicate "rdf:type":rdfBuilder.model.createResource(V.MAONT_NS[1]+"MediaFragment")
													predicate "ma:isFragmentOf":rdfBuilder.model.createResource(resURI)
													predicate "dcterms:creator":rdfBuilder.model.createResource(getUserBaseURI()+a.owner?.id)
													property "ma:locator":utilsService.attachFragmentToURI(resource.url?.url,f)
													predicate("rdfs:seeAlso"){
														subject(utilsService.attachFragmentToURI(getHTMLRepresentationURI(redirectData),f)){
															property "dc:format":"application/xhtml+xml"
														}
													}
													subject(mfURIStr)
													{
														predicate("rdfs:isDefinedBy")
														{
															subject(utilsService.attachFragmentToURI(getResourceRDFRepresentationURI(redirectData),f)){
																property "dc:format":"application/rdf+xml"
															}
														}
													}
													subject(mfURIStr){
													//check the source resource of the annotation and add predicates to the media fragments
														switch(sourceResource.class.name){
															case "org.synote.resource.compound.SynmarkResource":
																predicate "rdf:type":rdfBuilder.model.createResource(V.OAC_NS[1]+"Target")
																if(sourceResource.title)
																	property "ma:title":sourceResource.title?.encodeAsHTML()
																	
																if(sourceResource.tags?.size() > 0)
																{
																	for(t in sourceResource.tags)
																	{
																		//the keyword should be a URI, see http://www.w3.org/TR/mediaont-10/#correspondance-id3
																		predicate "ma:hasKeyword":rdfBuilder.model.createResource(getResourceBaseURI()+t.id)
																	}
																}
																//Yunjia: we can use RDFa richtext editor tinyMCE etc, and then use some programme
																//to extract RDFa triples from the note and embed it here!
																if(sourceResource.note)
																{
																	//parse note.content and get triples first
																	property "ma:description":sourceResource.note?.content?.encodeAsHTML()
																	parseRDFa(sourceResource.note?.content, rdfBuilder.model, mfURIStr)
																}
																
																break
															case "org.synote.resource.compound.PresentationResource":
																def slide = PresentationSlide.findByPresentationAndIndex(sourceResource,syn.sourceStart)
																if(slide)
																	predicate "ma:hasRelatedImage":rdfBuilder.model.createResource(getResourceBaseURI()+slide.id)
																break
															//check both cue and webvttresource
															case "org.synote.resource.compound.WebVTTResource":
																def cue = WebVTTCue.findByWebVTTFileAndCueIndex(sourceResource,syn.sourceStart)
																if(cue)
																{
																	predicate "ma:hasSubtitling":rdfBuilder.model.createResource(getResourceBaseURI()+cue.id)
																}
																subject(""){
																	predicate "ma:hasSubtitling":rdfBuilder.model.createResource(getResourceBaseURI()+sourceResource.id)
																}
																break
															default:
																break
														}
													}
												}
											}
										}
									}
								}
								break
							case "org.synote.resource.compound.SynmarkResource":
								predicate "rdf:type":rdfBuilder.model.createResource(V.OAC_NS[1]+"Body")
								predicate "rdf:type":rdfBuilder.model.createResource(V.ORE_NS[1]+"Aggregation")
								if(resource.note)
								{
									predicate "ore:aggregates":rdfBuilder.model.createResource(getResourceBaseURI()+resource.note?.id)
								}
								if(resource.tags?.size()>0)
								{
									for(t in resource.tags)
									{
										predicate "ore:aggregates":rdfBuilder.model.createResource(getResourceBaseURI()+t.id)
									}
								}
								break;
							case "org.synote.resource.single.text.SynmarkTag":
								predicate "rdf:type":rdfBuilder.model.createResource(V.ORE_NS[1]+"AggregatedResource")
								predicate "ore:isAggregatedBy":	rdfBuilder.model.createResource(getResourceBaseURI()+resource.synmark?.id)
								predicate "rdfs:label":resource.content?.encodeAsHTML()
								break;
							case "org.synote.resource.single.text.SynmarkTextNote":
								predicate "rdf:type":rdfBuilder.model.createResource(V.ORE_NS[1]+"AggregatedResource")
								predicate "ore:isAggregatedBy":	rdfBuilder.model.createResource(getResourceBaseURI()+resource.synmark?.id)
								predicate "rdfs:label":resource.content?.encodeAsHTML()
								break;
							case "org.synote.resource.compound.PresentationResource":
								predicate "rdf:type":rdfBuilder.model.createResource(V.OAC_NS[1]+"Body")
								predicate "rdf:type":rdfBuilder.model.createResource(V.ORE_NS[1]+"Aggregation")
								if(resource.slides?.size()>0)
								{
									for(s in resource.slides)
									{
										predicate "ore:aggregates":rdfBuilder.model.createResource(getResourceBaseURI()+s.id)
									}
								}
								break;
							case "org.synote.resource.single.binary.PresentationSlide":
								//locator
								predicate "rdf:type":rdfBuilder.model.createResource(V.ORE_NS[1]+"AggregatedResource")
								predicate "rdf:type":rdfBuilder.model.createResource(V.MAONT_NS[1]+"Image")
								predicate "ore:isAggregatedBy":	rdfBuilder.model.createResource(getResourceBaseURI()+resource.presentation?.id)
								predicate "ma:locator":resource.url
								break;
							case "org.synote.resource.compound.WebVTTResource":
								predicate "rdf:type":rdfBuilder.model.createResource(V.OAC_NS[1]+"Body")
								predicate "rdf:type":rdfBuilder.model.createResource(V.ORE_NS[1]+"Aggregation")
								if(resource.cues?.size()>0)
								{
									for(c in resource.cues)
									{
										predicate "ore:aggregates":rdfBuilder.model.createResource(getResourceBaseURI()+c.id)
									}
								}
								break;
							case "org.synote.resource.single.text.WebVTTCue":
								predicate "rdf:type":rdfBuilder.model.createResource(V.ORE_NS[1]+"AggregatedResource")
								predicate "ore:isAggregatedBy":	rdfBuilder.model.createResource(getResourceBaseURI()+resource.webVTTFile?.id)
								predicate "rdfs:label":resource.content?.encodeAsHTML()
								break;
							default:
								break;
						}
					}
				}
				return
			}
			
		}
	}
	
	/*
	* The reusable method to build annotation data
	* params: output stream
	*/
	   def buildAnnotationData(outputStream,annotation) throws RDFGenerationException
	   {
		   if(!outputStream)
		   {
			   throw new RDFGenerationException("Missing output stream")
			   return
		   }
		   else if(!annotation)
		   {
			   throw new RDFGenerationException("Cannot find annotation")
			   return
		   }
		   else
		   {
			   RedirectData redirectData = getRedirectDataFromAnnotation(annotation)
			   if(!redirectData)
			   {
				   throw new RDFGenerationException("Cannot get RDF from the annotation. 3 possibilities: (1) the resource is not published as linked data yet. (2) You are note permitted to see it (3) it is not related to any annotation")
				   return
			   }
			   else
			   {
				   //Now start to build rdf based on different resource classes
				   def rdfBuilder = new RDFBuilder(outputStream)
				   rdfBuilder.model.setNsPrefix("dc",V.DC_NS[1])
				   rdfBuilder.model.setNsPrefix("rdfs",V.RDFS_NS[1])
				   rdfBuilder.model.setNsPrefix("dcterms",V.DCTERMS_NS[1])
				   rdfBuilder.model.setNsPrefix("oac",V.OAC_NS[1])
				   def output = null
				   output = rdfBuilder.xml{
					   defaultNamespace getAnnotationBaseURI()
					   namespace rdf:V.RDF_NS[1]
					   namespace rdfs:V.RDFS_NS[1]
					   namespace dc:V.DC_NS[1]
					   namespace dcterms:V.DCTERMS_NS[1]
					   namespace oac:V.OAC_NS[1]
					   subject(getAnnotationBaseURI()+annotation.id){
						   predicate "rdf:type":rdfBuilder.model.createResource(V.OAC_NS[1]+"Annotation")
						   if(redirectData.compoundResource?.owner?.id)
						   {
							   property "dcterms:creator":rdfBuilder.model.createResource(getUserBaseURI()+redirectData.compoundResource.owner.id)
							   property "oac:hasBody":rdfBuilder.model.createResource(getResourceBaseURI()+redirectData.compoundResource.id)
						   }
						   property "oac:hasTarget":rdfBuilder.model.createResource(
								   utilsService.attachFragmentToURI(getResourceBaseURI()+redirectData.recording.id,
									   (redirectData.hasFragment()?(redirectData.getFragment()):"")))
						   predicate("rdfs:seeAlso"){
							   subject(getHTMLRepresentationURI(redirectData)){
								   property "dc:format":"application/xhtml+xml"
							   }
						   }
						   
						   subject(getAnnotationBaseURI()+annotation.id){
							   predicate("rdfs:isDefinedBy"){
								   subject(getAnnotationDataBaseURI()+annotation.id){
									   property "dc:format":"application/xhtml+xml"
								   }
							   }
						   }
					   }
				   }
				   return
			   } 
		   }
	   }
	   
	   /*
	   * The reusable method to build user data
	   * params: output stream
	   */
	  def buildUserData(outputStream,user) throws RDFGenerationException
	  {
		  if(!outputStream)
		  {
			  throw new RDFGenerationException("Missing output stream")
			  return
		  }
		  else if(!user || securityService.isAdmin(user))
		  {
			  throw new RDFGenerationException("Cannot find user")
			  return
		  }
		  else
		  {
			  def rdfBuilder = new RDFBuilder(outputStream)
			  rdfBuilder.model.setNsPrefix("foaf",V.FOAF_NS[1])
			  def output = null
			  output = rdfBuilder.xml{
				  defaultNamespace getUserBaseURI()
				  namespace rdf:V.RDF_NS[1]
				  //namespace rdfs:"http://www.w3.org/2000/01/rdf-schema#"
				  namespace foaf:V.FOAF_NS[1]
				  subject(getUserBaseURI()+user.id){
					  predicate "rdf:type":rdfBuilder.model.createResource(V.FOAF_NS[1]+"Person")
					  property "foaf:name":user.userName
					  property "foaf:mbox":user.email
					  property "foaf:givenName":user.firstName
					  property "foaf:familyName":user.lastName
				  }
			  }
			  return
		  }
	  }
	  
	  /*
	   * initialize the triple store with all namespace prefixes defined in Vocabularies
	   */
	  def initPrefixMapping(store)
	  {
		  Model model = SDBFactory.connectDefaultModel(store)
		  store.getLoader().setChunkSize(5000)
		  store.getLoader().startBulkUpdate()
		  //write namespaces to triple store
		  model.notifyEvent(GraphEvents.startRead)
		  try
		  {  
			  V.getVocabularies().each{voca->
			  		model.setNsPrefix(voca[0], voca[1])
			  }
		  }
		  finally{
			  model.notifyEvent(GraphEvents.finishRead)
			  store.getLoader().finishBulkUpdate();
			  model.close()
			  store.close()
		  }
	  }
	  
	  /*
	   * Check if the named entity has already been extracted using the same extractor for the same media resource or fragment
	   * params:
	   * extraction: the NERD definition of Extraction result
	   * extractor: the name of the extractor
	   * mediaUri: the multimedia URI or media fragment URI
	   * 
	   * return:
	   * true : means there is duplicated Named entity
	   * false : means there is no duplicated named entity
	   */
	  def synchronized checkDuplicateNE(extraction, extractor,resource)
	  {
		  String prefixString = V.getPrefixListString()
		  
		  String resourceURI = getResourceBaseURI()+resource.id
		  
		  def queryString = """
				  ${prefixString}
				  SELECT ?ne ?anno WHERE {
					    <${resourceURI}> dcterms:hasPart ?ne.
		  				?ne rdfs:label "${extraction.getEntity()}".
		  				?anno oac:hasBody ?ne.
		  				?anno rdfs:comment "${extractor}".
				} 
		  """
		  
		  def store = getSDBInstance()
		  Dataset ds = DatasetStore.create(store)
		  Query query = QueryFactory.create(queryString)
		  QueryExecution qexec = QueryExecutionFactory.create(query, ds)
		  String jsonStr = ""
		  def duplicated = false
		  try
		  {
			  ResultSet results = null
			  results = qexec.execSelect()
			 
			  if(results.hasNext())
			  {
				  duplicated = true
			  }
			  
		  }
		  catch(Exception ex)
		  {
			  throw ex
		  }
		  finally
		  {
			  qexec.close()
			  return duplicated
		  }
		  
	  }
	  
	  /*
	   * return -1 if, the review doesn't exist
	   * return 0 if the current review rating is 0
	   * return 1 if the current review rating is 1
	   */
	  def synchronized checkDuplicateReview(idex,userId)
	  {
		  String prefixString = V.getPrefixListString()
		  
		  String nerdExURI = getNERDAnnotationBaseURI()+idex
		  String userURI = getUserBaseURI()+userId
		  def queryString = """
				  ${prefixString}
				SELECT ?rev ?rating WHERE {
					    <${nerdExURI}> review:hasReview ?rev.
				        ?rev review:reviewer <${userURI}>.
				        ?rev review:rating ?rating.
				} 
		  """
		  
		  def store = getSDBInstance()
		  Dataset ds = DatasetStore.create(store)
		  Query query = QueryFactory.create(queryString)
		  QueryExecution qexec = QueryExecutionFactory.create(query, ds)
		  String jsonStr = ""
		  int duplicated = -1
		  try
		  {
			  ResultSet results = null
			  results = qexec.execSelect()
			 
			  if(results.hasNext())
			  {
				  QuerySolution soln = results.nextSolution()
				  Literal l = soln.getLiteral("rating")
				  duplicated = l.getInt()
			  }
			  
		  }
		  catch(Exception ex)
		  {
			  throw ex
		  }
		  finally
		  {
			  qexec.close()
			  return duplicated
		  }
		  
	  }
	  /*
	   * Save nerd extraction result to triple store
	   * params:
	   * extractions: List<fr.eurecom.nerd.client.schema.Extraction>
	   * multimedia: the compound multimedia resource the named entity annotates
	   * resource: a Multimedia, Synmark or Transcript Cue resource that directly related to this named entity
	   * synpoint: providing the start and end time that the named entity annotates
	   * extractor: a String, the extractor's name
	   */
	  def synchronized saveNERDToTripleStroe(extractions,multimedia,resource,synpoint,extractor)
	  {
  			if(grailsApplication.config.jena.enabled != true)
			{
				return false
			}
			
			if(!multimedia || !resource)
			{
				return false	
			}
			
			//No extractions
			if(extractions?.size() <=0)
			{
				return false
			}
					
			def store = getSDBInstance()
			Model model = SDBFactory.connectDefaultModel(store)
			store.getLoader().setChunkSize(5000)
			store.getLoader().startBulkUpdate()
			//write triples to triple store
			
			model.notifyEvent(GraphEvents.startRead)
			try {
				/*
				 * s, o naming:
				 * s or p + _ + name
				 * p naming:
				 * p+_+nsprefix+_+name
				 */
				String mediaUri = ""
				if(synpoint) //create media fragment
				{
					String resURI = getResourceBaseURI()+multimedia.id
					String f = getFragmentStringFromSynpoint(synpoint)
					mediaUri = utilsService.attachFragmentToURI(resURI,f)
					JenaResource s_multimedia = model.createResource(resURI)
					JenaProperty p_fragment = model.createProperty(V.MAONT_NS[1]+"hasFragment")
					JenaResource o_fragment = model.createResource(mediaUri)
					
					if(!model.contains(s_multimedia,p_fragment,o_fragment))
					{
						model.add(s_multimedia,p_fragment,o_fragment)
					}
				}
				else
					mediaUri = getResourceBaseURI()+multimedia.id
					
				log.debug("extractions size:"+extractions?.size());
				
				for(Extraction e : extractions )
			    {
				   
				   if(checkDuplicateNE(e, extractor,resource))
				   {
					   log.debug("duplicated:"+e.getEntity())
					   continue
				   }
				   
				   JenaResource ne = null
				   if(e.getUri() == null || e.getUri().equals("null") || e.getUri().equals("NORDF"))
				   {
					   //println "getEntity:"+e.getEntity()
					   ne = model.createResource(new AnonId("_b_"+e.getIdExtraction()))
				   }
				   else
				   {
					   //println "getUri:"+e.getUri()
					   ne = model.createResource(e.getUri())
				   }
				   
				   //description for named entities	
				   JenaProperty p_rdf_type = model.createProperty(V.RDF_NS[1]+"type")
				   JenaResource o_nerdType = model.createResource(e.getNerdType())
				   model.add(ne,p_rdf_type,o_nerdType)
				   log.debug("type");
				   
				   JenaProperty p_dc_type = model.createProperty(V.DC_NS[1]+"type")
				   String dc_type = e.getType()
				   if(!e.getType())
				   		dc_type = "Thing" //Literal cannot be null since Jena 2.0
				   Literal o_dcType = model.createLiteral(dc_type)
				   model.add(ne,p_dc_type,o_dcType)
				   
				   JenaProperty p_rdfs_label = model.createProperty(V.RDFS_NS[1]+"label")
				   Literal o_entityName = model.createLiteral(e.getEntity())
				   model.add(ne,p_rdfs_label,o_entityName)
				   log.debug("label");
				   
				   //description for OAC annotations
				   JenaResource s_annotation = model.createResource(getNERDAnnotationBaseURI()+e.getIdExtraction())
				   JenaResource o_annotationType = model.createResource(V.OAC_NS[1]+"Annotation")
				   model.add(s_annotation,p_rdf_type,o_annotationType)
				   
				   //the identifier is the extraction id
				   JenaProperty p_dc_identifier = model.createProperty(V.DC_NS[1]+"identifier")
				   Literal o_idExtraction = model.createLiteral(String.valueOf(e.getIdExtraction()))
				   model.add(s_annotation,p_dc_identifier,o_idExtraction)
				   
				   JenaProperty p_oac_hasBody = model.createProperty(V.OAC_NS[1]+"hasBody")
				   model.add(s_annotation,p_oac_hasBody, ne)
				   
				   JenaProperty p_rdfs_comment = model.createProperty(V.RDFS_NS[1]+"comment")
				   Literal o_extractor = model.createLiteral(extractor)
				   model.add(s_annotation,p_rdfs_comment,o_extractor)
				   
				   StringBuilder otherResult = new StringBuilder()
				   otherResult.append("confidence:"+e.getConfidence()+",")
				   otherResult.append("relevance:"+e.getRelevance()+",")
				   //otherResult.append("startChar:"+e.getStartChar()) will be saved as str:beginIndex
				   //otherResult.append("endChar:"+e.getEndChar()+",") will be saved as str:endIndex
				   JenaProperty p_dc_description = model.createProperty(V.DC_NS[1]+"description")
				   Literal o_otherResult = model.createLiteral(otherResult.toString())
				   model.add(s_annotation,p_dc_description,o_otherResult)
				   
				   //add startChar and endChar information
				   if(e.getStartChar() != null)
				   {
					   JenaProperty p_str_beginIndex = model.createProperty(V.STR_NS[1]+"beginIndex")
					   Literal o_str_start = model.createTypedLiteral(String.valueOf(e.getStartChar()),XSDDatatype.XSDinteger)
					   model.add(s_annotation,p_str_beginIndex,o_str_start)
				   }
				   if(e.getEndChar() != null)
				   {
					   JenaProperty p_str_endIndex = model.createProperty(V.STR_NS[1]+"endIndex")
					   Literal o_str_end = model.createTypedLiteral(String.valueOf(e.getEndChar()),XSDDatatype.XSDinteger)
					   model.add(s_annotation,p_str_endIndex,o_str_end)
				   }
				   
				   JenaProperty p_dcterms_created = model.createProperty(V.DCTERMS_NS[1]+"created")
				   String c_time = Utils.calendarToXSDDateTimeString(new GregorianCalendar())
				   Literal o_created = model.createTypedLiteral(c_time, XSDDatatype.XSDdateTime)
				   model.add(s_annotation,p_dcterms_created,o_created)
				   
				   JenaProperty p_dcterms_modified = model.createProperty(V.DCTERMS_NS[1]+"modified")
				   String m_time = Utils.calendarToXSDDateTimeString(new GregorianCalendar())
				   Literal o_modified = model.createTypedLiteral(m_time, XSDDatatype.XSDdateTime)
				   model.add(s_annotation,p_dcterms_created,o_modified)
				   
				   JenaResource media = model.createResource(mediaUri)
				   JenaProperty p_oac_hasTarget = model.createProperty(V.OAC_NS[1]+"hasTarget")
				   model.add(s_annotation,p_oac_hasTarget,media)
				   
				   JenaProperty p_oac_annotates = model.createProperty(V.OAC_NS[1]+"annotates")
				   model.add(ne,p_oac_annotates,media)
				   
				   //dcterms:hasPart, relate Multimedia, Synmark or Cue to named entity
				   JenaResource s_resource = model.createResource(getResourceBaseURI()+resource.id)
				   JenaProperty p_dcterms_haspart = model.createProperty(V.DCTERMS_NS[1]+"hasPart")
				   model.add(s_resource, p_dcterms_haspart, ne)
				   
				   if(synpoint) //create media fragment
				   {   
					   model.setNsPrefix(V.NSA_NS[0],V.NSA_NS[1]) //ninsuna time
					   //npt, temporalstart, temporalend
					   JenaResource o_nsa_type = model.createResource(V.NSA_NS[1]+"TemperalFragment")
					   model.add(media,p_rdf_type,o_nsa_type)
					   
					   JenaProperty p_nsa_unit = model.createProperty(V.NSA_NS[1]+"temporalUnit")
					   JenaResource o_nsa_npt = model.createResource(V.NSA_NS[1]+"npt")
					   model.add(media,p_nsa_unit,o_nsa_npt)
					   
					   //nsa ontology use double
					   double divide = 1000
					   double start = synpoint.targetStart/divide
					   
					   JenaProperty p_nsa_start = model.createProperty(V.NSA_NS[1]+"temporalStart")
					   Literal o_nsa_start = model.createTypedLiteral(start.toString(),XSDDatatype.XSDdouble)
					   model.add(media,p_nsa_start,o_nsa_start)
					   
					   if(synpoint.targetEnd)
					   {
						   double end = synpoint.targetEnd/divide
						   JenaProperty p_nsa_end = model.createProperty(V.NSA_NS[1]+"temporalEnd")
						   Literal o_nsa_end = model.createTypedLiteral(end.toString(),XSDDatatype.XSDdouble)
						   model.add(media,p_nsa_end,o_nsa_end)
					   }
				   }
				   else //normal multimedia resource
				   {
					   JenaResource o_ma_type = model.createResource(V.MAONT_NS[1]+"MediaResource")
					   model.add(media,p_rdf_type,o_ma_type)
				   }
				   
				   JenaResource o_schema_type = null
				   if(multimedia.isVideo)
				   {
					   o_schema_type = model.createResource(V.SCHEMAORG_NS[1]+"VideoObject")
				   }
				   else
				   {
					   o_schema_type = model.createResource(V.SCHEMAORG_NS[1]+"AudioObject")
				   }
				   model.add(media,p_rdf_type,o_schema_type)
			    }
			}
			catch(Exception ex)
			{
				//println ex.getMessage()
				throw ex
			} finally {
			  model.notifyEvent(GraphEvents.finishRead)
			  store.getLoader().finishBulkUpdate();
			  model.close()
			  return
			  //store.close()
			}
	  }
	  
	  /*
	   * Save user review of the named entity to triple store
	   * idex:the extraction id
	   * userId: the reviewer's synote user id
	   */
	  def synchronized saveReviewToTripleStore(rating,idex,userId)
	  {
		  if(grailsApplication.config.jena.enabled != true)
		  {
			  return
		  }
		  
		  log.debug("rating:"+rating)
		  
		  //Check if review exists, if yes, ignore the rest
		  int duplicated = checkDuplicateReview(idex,userId)
		  log.debug("duplicated:"+duplicated)
		  if(rating == duplicated)
		  {
				return  
		  }
		  
		  def store = getSDBInstance()
		  
		  if(duplicated != -1)
		  {
			   Dataset ds = DatasetStore.create(store)
			   //GraphStore graphStore = GraphStoreFactory.create(ds)
			   String prefixString = V.getPrefixListString()
			   String annoNerdURI = getNERDAnnotationBaseURI()+idex
			   String userURI = getUserBaseURI()+userId
			   
			   String updateString = """
			   		${prefixString}
					MODIFY DELETE {?rev review:rating ?oldRating}
					INSERT {?rev review:rating ${rating}}
					WHERE {
			            <${annoNerdURI}> review:hasReview ?rev.
			            ?rev review:reviewer <${userURI}>.
			            ?rev review:rating ?oldRating.
					} 
			   """
			   UpdateRequest req = UpdateFactory.create(updateString)
			   UpdateAction.execute(req,ds)
			   
			   return
		  }
		  
		  
		  Model model = SDBFactory.connectDefaultModel(store)
		  store.getLoader().setChunkSize(5000)
		  store.getLoader().startBulkUpdate()
		  //write triples to triple store
		  
		  model.notifyEvent(GraphEvents.startRead)
		  try {
			  JenaResource s_annotation = model.createResource(getNERDAnnotationBaseURI()+idex)
			  JenaProperty p_review_hasReview = model.createProperty(V.REVIEW_NS[1]+"hasReview")
			  JenaResource o_bnode_review = model.createResource(new AnonId("_b_"+idex+"_"+userId))
			  model.add(s_annotation,p_review_hasReview,o_bnode_review)
			  
			  JenaProperty p_rdf_type = model.createProperty(V.RDF_NS[1]+"type")
			  JenaResource o_review = model.createResource(V.REVIEW_NS[1]+"Review")
			  model.add(o_bnode_review,p_rdf_type,o_review)
			  
			  JenaProperty p_reviewer = model.createProperty(V.REVIEW_NS[1]+"reviewer")
			  JenaResource o_reviewer = model.createResource(getUserBaseURI()+userId)
			  model.add(o_bnode_review,p_reviewer,o_reviewer)
			  
			  JenaProperty p_rating = model.createProperty(V.REVIEW_NS[1]+"rating")
			  Literal o_rating = model.createTypedLiteral(rating,XSDDatatype.XSDinteger)
			  model.add(o_bnode_review,p_rating,o_rating)
			  
			  JenaProperty p_dcterms_created = model.createProperty(V.DCTERMS_NS[1]+"created")
			  String c_time = Utils.calendarToXSDDateTimeString(new GregorianCalendar())
			  Literal o_created = model.createTypedLiteral(c_time, XSDDatatype.XSDdateTime)
			  model.add(o_bnode_review,p_dcterms_created,o_created)

			  JenaProperty p_dcterms_modified = model.createProperty(V.DCTERMS_NS[1]+"modified")
			  String m_time = Utils.calendarToXSDDateTimeString(new GregorianCalendar())
			  Literal o_modified = model.createTypedLiteral(m_time, XSDDatatype.XSDdateTime)
			  model.add(o_bnode_review,p_dcterms_created,o_modified)
		  }
		  catch(Exception ex)
		  {
			  println ex.getMessage()
			  ex.printStackTrace()
			  throw ex
		  } finally {
		  	log.debug("save review finally")
			model.notifyEvent(GraphEvents.finishRead)
			store.getLoader().finishBulkUpdate();
			model.close()
			return
			//store.close()
		  }
	  }
	  
	  /*
	   * Get all named entites related to a particular resource
	   * Here, the MultimediaResource only means the tags and note of a multimedia resource
	   */
	  def getNEAsJSON(resource,user)
	  {
		  String prefixString = V.getPrefixListString()
		  
		  String reviewString = ""
		  if(user)
		  {
			  String userURI = getUserBaseURI()+user.id
			  reviewString= """
						OPTIONAL{
							?anno review:hasReview ?rev.
							?rev review:reviewer <${userURI}>;
								   review:rating ?rating.
					   }"""
		  }
		  
		  String resourceURI = getResourceBaseURI()+resource.id
		  def queryString = """
		  		${prefixString}
				SELECT Distinct ?ne ?type ?nerdtype ?entity ?extr ?idex ?rating WHERE {
					<${resourceURI}> dcterms:hasPart ?ne .
				       ?ne dc:type ?type .
				       ?ne rdfs:label ?entity .
				       ?ne rdf:type ?nerdtype .
				       ?anno oac:hasBody ?ne .
				       ?anno rdfs:comment ?extr .
		  			   ?anno dc:identifier ?idex .
		  			   ${reviewString}
				}
		  """
		  
		  //println "queryString:"+queryString
		  def store = getSDBInstance()
		  Dataset ds = DatasetStore.create(store)
		  Query query = QueryFactory.create(queryString)
		  QueryExecution qexec = QueryExecutionFactory.create(query, ds)
		  String jsonStr = ""
		  try
		  {
			  def output = new ByteArrayOutputStream()
			  
			  //Model model = SDBFactory.connectDefaultModel(store)
			  ResultSet results = null
			  results = qexec.execSelect()
			  ResultSetFormatter.outputAsJSON(output,results)
			  jsonStr = output.toString()
			  //println "jsonStr:"+jsonStr 
		  }
		  catch(Exception ex)
		  {
			  throw ex
		  }
		  finally
		  {
			  qexec.close()
			  return JSON.parse(jsonStr)
		  }
	  }
}
