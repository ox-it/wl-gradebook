/**********************************************************************************
*
* $Id$
*
***********************************************************************************
*
* Copyright (c) 2005, 2006 The Regents of the University of California, The MIT Corporation
*
* Licensed under the Educational Community License, Version 1.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*      http://www.opensource.org/licenses/ecl1.php
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*
**********************************************************************************/
package org.sakaiproject.component.gradebook;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hibernate.HibernateException;
import org.hibernate.Query;
import org.hibernate.Session;
import org.hibernate.StaleObjectStateException;
import org.sakaiproject.section.api.SectionAwareness;
import org.sakaiproject.section.api.coursemanagement.EnrollmentRecord;
import org.sakaiproject.section.api.facade.Role;
import org.sakaiproject.service.gradebook.shared.ConflictingAssignmentNameException;
import org.sakaiproject.service.gradebook.shared.ConflictingCategoryNameException;
import org.sakaiproject.service.gradebook.shared.GradebookNotFoundException;
import org.sakaiproject.service.gradebook.shared.StaleObjectModificationException;
import org.sakaiproject.tool.gradebook.AbstractGradeRecord;
import org.sakaiproject.tool.gradebook.Assignment;
import org.sakaiproject.tool.gradebook.AssignmentGradeRecord;
import org.sakaiproject.tool.gradebook.Category;
import org.sakaiproject.tool.gradebook.CourseGrade;
import org.sakaiproject.tool.gradebook.CourseGradeRecord;
import org.sakaiproject.tool.gradebook.GradeMapping;
import org.sakaiproject.tool.gradebook.Gradebook;
import org.sakaiproject.tool.gradebook.GradebookProperty;
import org.sakaiproject.tool.gradebook.facades.Authn;
import org.sakaiproject.tool.gradebook.facades.EventTrackingService;
import org.springframework.orm.hibernate3.HibernateCallback;
import org.springframework.orm.hibernate3.HibernateOptimisticLockingFailureException;
import org.springframework.orm.hibernate3.support.HibernateDaoSupport;

import java.lang.IllegalArgumentException;

/**
 * Provides methods which are shared between service business logic and application business
 * logic, but not exposed to external callers.
 */
public abstract class BaseHibernateManager extends HibernateDaoSupport {
	private static final Log log = LogFactory.getLog(BaseHibernateManager.class);

    // Oracle will throw a SQLException if we put more than this into a
    // "WHERE tbl.col IN (:paramList)" query.
    public static int MAX_NUMBER_OF_SQL_PARAMETERS_IN_LIST = 1000;

    protected SectionAwareness sectionAwareness;
    protected Authn authn;
    protected EventTrackingService eventTrackingService;

    // Local cache of static-between-deployment properties.
    protected Map propertiesMap = new HashMap();

    public Gradebook getGradebook(String uid) throws GradebookNotFoundException {
    	List list = getHibernateTemplate().find("from Gradebook as gb where gb.uid=?",
    		uid);
		if (list.size() == 1) {
			return (Gradebook)list.get(0);
		} else {
            throw new GradebookNotFoundException("Could not find gradebook uid=" + uid);
        }
    }

    public boolean isGradebookDefined(String gradebookUid) {
        String hql = "from Gradebook as gb where gb.uid=?";
        return getHibernateTemplate().find(hql, gradebookUid).size() == 1;
    }

    protected List getAssignments(Long gradebookId, Session session) throws HibernateException {
        List assignments = session.createQuery(
        	"from Assignment as asn where asn.gradebook.id=? and asn.removed=false").
        	setLong(0, gradebookId.longValue()).
        	list();
        return assignments;
    }

    protected List getCountedStudentGradeRecords(Long gradebookId, String studentId, Session session) throws HibernateException {
        return session.createQuery(
        	"select agr from AssignmentGradeRecord as agr, Assignment as asn where agr.studentId=? and agr.gradableObject=asn and asn.removed=false and asn.notCounted=false and asn.gradebook.id=?").
        	setString(0, studentId).
        	setLong(1, gradebookId.longValue()).
        	list();
    }

    /**
     */
    public CourseGrade getCourseGrade(Long gradebookId) {
        return (CourseGrade)getHibernateTemplate().find(
                "from CourseGrade as cg where cg.gradebook.id=?",
                gradebookId).get(0);
    }

    /**
     * Gets the course grade record for a student, or null if it does not yet exist.
     *
     * @param studentId The student ID
     * @param session The hibernate session
     * @return A List of grade records
     *
     * @throws HibernateException
     */
    protected CourseGradeRecord getCourseGradeRecord(Gradebook gradebook,
            String studentId, Session session) throws HibernateException {
        return (CourseGradeRecord)session.createQuery(
        	"from CourseGradeRecord as cgr where cgr.studentId=? and cgr.gradableObject.gradebook=?").
        	setString(0, studentId).
        	setEntity(1, gradebook).
        	uniqueResult();
    }

    public String getGradebookUid(Long id) {
        return ((Gradebook)getHibernateTemplate().load(Gradebook.class, id)).getUid();
    }

	protected Set getAllStudentUids(String gradebookUid) {
		List enrollments = getSectionAwareness().getSiteMembersInRole(gradebookUid, Role.STUDENT);
        Set studentUids = new HashSet();
        for(Iterator iter = enrollments.iterator(); iter.hasNext();) {
            studentUids.add(((EnrollmentRecord)iter.next()).getUser().getUserUid());
        }
        return studentUids;
	}

	protected Map getPropertiesMap() {

		return propertiesMap;
	}

	public String getPropertyValue(final String name) {
		String value = (String)propertiesMap.get(name);
		if (value == null) {
			List list = getHibernateTemplate().find("from GradebookProperty as prop where prop.name=?",
				name);
			if (!list.isEmpty()) {
				GradebookProperty property = (GradebookProperty)list.get(0);
				value = property.getValue();
				propertiesMap.put(name, value);
			}
		}
		return value;
	}
	public void setPropertyValue(final String name, final String value) {
		GradebookProperty property;
		List list = getHibernateTemplate().find("from GradebookProperty as prop where prop.name=?",
			name);
		if (!list.isEmpty()) {
			property = (GradebookProperty)list.get(0);
		} else {
			property = new GradebookProperty(name);
		}
		property.setValue(value);
		getHibernateTemplate().saveOrUpdate(property);
		propertiesMap.put(name, value);
	}

	/**
	 * Oracle has a low limit on the maximum length of a parameter list
	 * in SQL queries of the form "WHERE tbl.col IN (:paramList)".
	 * Since enrollment lists can sometimes be very long, we've replaced
	 * such queries with full selects followed by filtering. This helper
	 * method filters out unwanted grade records. (Typically they're not
	 * wanted because they're either no longer officially enrolled in the
	 * course or they're not members of the selected section.)
	 */
	protected List filterGradeRecordsByStudents(Collection gradeRecords, Collection studentUids) {
		List filteredRecords = new ArrayList();
		for (Iterator iter = gradeRecords.iterator(); iter.hasNext(); ) {
			AbstractGradeRecord agr = (AbstractGradeRecord)iter.next();
			if (studentUids.contains(agr.getStudentId())) {
				filteredRecords.add(agr);
			}
		}
		return filteredRecords;
	}

	protected Assignment getAssignmentWithoutStats(String gradebookUid, String assignmentName, Session session) throws HibernateException {
		return (Assignment)session.createQuery(
			"from Assignment as asn where asn.name=? and asn.gradebook.uid=? and asn.removed=false").
			setString(0, assignmentName).
			setString(1, gradebookUid).
			uniqueResult();
	}

	protected void updateAssignment(Assignment assignment, Session session)
		throws ConflictingAssignmentNameException, HibernateException {
		// Ensure that we don't have the assignment in the session, since
		// we need to compare the existing one in the db to our edited assignment
		session.evict(assignment);

		Assignment asnFromDb = (Assignment)session.load(Assignment.class, assignment.getId());
		int numNameConflicts = ((Integer)session.createQuery(
				"select count(go) from GradableObject as go where go.name = ? and go.gradebook = ? and go.removed=false and go.id != ?").
				setString(0, assignment.getName()).
				setEntity(1, assignment.getGradebook()).
				setLong(2, assignment.getId().longValue()).
				uniqueResult()).intValue();
		if(numNameConflicts > 0) {
			throw new ConflictingAssignmentNameException("You can not save multiple assignments in a gradebook with the same name");
		}

		session.evict(asnFromDb);
		session.update(assignment);
	}

    protected AssignmentGradeRecord getAssignmentGradeRecord(Assignment assignment, String studentUid, Session session) throws HibernateException {
		return (AssignmentGradeRecord)session.createQuery(
			"from AssignmentGradeRecord as agr where agr.studentId=? and agr.gradableObject.id=?").
			setString(0, studentUid).
			setLong(1, assignment.getId().longValue()).
			uniqueResult();
	}

    public Long createAssignment(final Long gradebookId, final String name, final Double points, final Date dueDate, final Boolean isNotCounted, final Boolean isReleased) throws ConflictingAssignmentNameException, StaleObjectModificationException {
        HibernateCallback hc = new HibernateCallback() {
            public Object doInHibernate(Session session) throws HibernateException {
                Gradebook gb = (Gradebook)session.load(Gradebook.class, gradebookId);
                int numNameConflicts = ((Integer)session.createQuery(
                        "select count(go) from GradableObject as go where go.name = ? and go.gradebook = ? and go.removed=false").
                        setString(0, name).
                        setEntity(1, gb).
                        uniqueResult()).intValue();
                if(numNameConflicts > 0) {
                    throw new ConflictingAssignmentNameException("You can not save multiple assignments in a gradebook with the same name");
                }

                   Assignment asn = new Assignment();
                   asn.setGradebook(gb);
                   asn.setName(name);
                   asn.setPointsPossible(points);
                   asn.setDueDate(dueDate);
                   if (isNotCounted != null) {
                       asn.setNotCounted(isNotCounted.booleanValue());
                   }

                   if(isReleased!=null){
                       asn.setReleased(isReleased.booleanValue());
                   }

                   // Save the new assignment
                   Long id = (Long)session.save(asn);

                   return id;
               }
           };

           return (Long)getHibernateTemplate().execute(hc);
    }

    public void updateGradebook(final Gradebook gradebook) throws StaleObjectModificationException {
        HibernateCallback hc = new HibernateCallback() {
            public Object doInHibernate(Session session) throws HibernateException {
                // Get the gradebook and selected mapping from persistence
                Gradebook gradebookFromPersistence = (Gradebook)session.load(
                        gradebook.getClass(), gradebook.getId());
                GradeMapping mappingFromPersistence = gradebookFromPersistence.getSelectedGradeMapping();

                // If the mapping has changed, and there are explicitly entered
                // course grade records, disallow this update.
                if (!mappingFromPersistence.getId().equals(gradebook.getSelectedGradeMapping().getId())) {
                    if(isExplicitlyEnteredCourseGradeRecords(gradebook.getId())) {
                        throw new IllegalStateException("Selected grade mapping can not be changed, since explicit course grades exist.");
                    }
                }

                // Evict the persisted objects from the session and update the gradebook
                // so the new grade mapping is used in the sort column update
                //session.evict(mappingFromPersistence);
                for(Iterator iter = gradebookFromPersistence.getGradeMappings().iterator(); iter.hasNext();) {
                    session.evict(iter.next());
                }
                session.evict(gradebookFromPersistence);
                try {
                    session.update(gradebook);
                    session.flush();
                } catch (StaleObjectStateException e) {
                    throw new StaleObjectModificationException(e);
                }

                return null;
            }
        };
        getHibernateTemplate().execute(hc);
    }

    public boolean isExplicitlyEnteredCourseGradeRecords(final Long gradebookId) {
        final Set studentUids = getAllStudentUids(getGradebookUid(gradebookId));
        if (studentUids.isEmpty()) {
            return false;
        }

        HibernateCallback hc = new HibernateCallback() {
            public Object doInHibernate(Session session) throws HibernateException {
                Integer total;
                if (studentUids.size() <= MAX_NUMBER_OF_SQL_PARAMETERS_IN_LIST) {
                    Query q = session.createQuery(
                            "select count(cgr) from CourseGradeRecord as cgr where cgr.enteredGrade is not null and cgr.gradableObject.gradebook.id=:gradebookId and cgr.studentId in (:studentUids)");
                    q.setLong("gradebookId", gradebookId.longValue());
                    q.setParameterList("studentUids", studentUids);
                    total = (Integer)q.list().get(0);
                    if (log.isInfoEnabled()) log.info("total number of explicitly entered course grade records = " + total);
                } else {
                    total = new Integer(0);
                    Query q = session.createQuery(
                            "select cgr.studentId from CourseGradeRecord as cgr where cgr.enteredGrade is not null and cgr.gradableObject.gradebook.id=:gradebookId");
                    q.setLong("gradebookId", gradebookId.longValue());
                    for (Iterator iter = q.list().iterator(); iter.hasNext(); ) {
                        String studentId = (String)iter.next();
                        if (studentUids.contains(studentId)) {
                            total = new Integer(1);
                            break;
                        }
                    }
                }
                return total;
            }
        };
        return ((Integer)getHibernateTemplate().execute(hc)).intValue() > 0;
    }

	public Authn getAuthn() {
        return authn;
    }
    public void setAuthn(Authn authn) {
        this.authn = authn;
    }

    protected String getUserUid() {
        return authn.getUserUid();
    }

    protected SectionAwareness getSectionAwareness() {
        return sectionAwareness;
    }
    public void setSectionAwareness(SectionAwareness sectionAwareness) {
        this.sectionAwareness = sectionAwareness;
    }

    protected EventTrackingService getEventTrackingService() {
        return eventTrackingService;
    }

    public void setEventTrackingService(EventTrackingService eventTrackingService) {
        this.eventTrackingService = eventTrackingService;
    }

    public void postEvent(String message,String objectReference){        
       eventTrackingService.postEvent(message,objectReference);
    }

    public Long createCategory(final Long gradebookId, final String name, final Double weight, final int drop_lowest) 
    throws ConflictingCategoryNameException, StaleObjectModificationException {
    	HibernateCallback hc = new HibernateCallback() {
    		public Object doInHibernate(Session session) throws HibernateException {
    			Gradebook gb = (Gradebook)session.load(Gradebook.class, gradebookId);
    			int numNameConflicts = ((Integer)session.createQuery(
    					"select count(ca) from Category as ca where ca.name = ? and ca.gradebook = ? and ca.removed=false ").
    					setString(0, name).
    					setEntity(1, gb).
    					uniqueResult()).intValue();
    			if(numNameConflicts > 0) {
    				throw new ConflictingCategoryNameException("You can not save multiple catetories in a gradebook with the same name");
    			}

    			Category ca = new Category();
    			ca.setGradebook(gb);
    			ca.setName(name);
    			ca.setWeight(weight);
    			ca.setDrop_lowest(drop_lowest);
    			ca.setRemoved(false);

    			Long id = (Long)session.save(ca);

    			return id;
    		}
    	};

    	return (Long)getHibernateTemplate().execute(hc);
    }

    public List getCategories(final Long gradebookId) throws HibernateException {
    	HibernateCallback hc = new HibernateCallback() {
    		public Object doInHibernate(Session session) throws HibernateException {
    			List categories = session.createQuery(
					"from Category as ca where ca.gradebook=? and ca.removed=false").
					setLong(0, gradebookId.longValue()).
					list();
    			return categories;
    		}
    	};
    	return (List) getHibernateTemplate().execute(hc);
    }
    
    public Long createAssignmentForCategory(final Long gradebookId, final Long categoryId, final String name, final Double points, final Date dueDate, final Boolean isNotCounted, final Boolean isReleased)
    throws ConflictingAssignmentNameException, StaleObjectModificationException, IllegalArgumentException
    {
    	if(gradebookId == null || categoryId == null)
    	{
    		throw new IllegalArgumentException("gradebookId or categoryId is null in BaseHibernateManager.createAssignmentForCategory");
    	}
    	
    	HibernateCallback hc = new HibernateCallback() {
    		public Object doInHibernate(Session session) throws HibernateException {
    			Gradebook gb = (Gradebook)session.load(Gradebook.class, gradebookId);
    			Category cat = (Category)session.load(Category.class, categoryId);
    			int numNameConflicts = ((Integer)session.createQuery(
    					"select count(go) from GradableObject as go where go.name = ? and go.gradebook = ? and go.removed=false").
    					setString(0, name).
    					setEntity(1, gb).
    					uniqueResult()).intValue();
    			if(numNameConflicts > 0) {
    				throw new ConflictingAssignmentNameException("You can not save multiple assignments in a gradebook with the same name");
    			}

    			Assignment asn = new Assignment();
    			asn.setGradebook(gb);
    			asn.setCategory(cat);
    			asn.setName(name);
    			asn.setPointsPossible(points);
    			asn.setDueDate(dueDate);
    			if (isNotCounted != null) {
    				asn.setNotCounted(isNotCounted.booleanValue());
    			}

    			if(isReleased!=null){
    				asn.setReleased(isReleased.booleanValue());
    			}

    			Long id = (Long)session.save(asn);

    			return id;
    		}
    	};

    	return (Long)getHibernateTemplate().execute(hc);
    }
    
    public List getAssignmentsForCategory(final Long categoryId) throws HibernateException{
    	HibernateCallback hc = new HibernateCallback() {
    		public Object doInHibernate(Session session) throws HibernateException {
    			List assignments = session.createQuery(
					"from Assignment as assign where assign.category=? and assign.removed=false").
					setLong(0, categoryId.longValue()).
					list();
    			return assignments;
    		}
    	};
    	return (List) getHibernateTemplate().execute(hc);
    }
    
    public Category getCategory(final Long categoryId) throws HibernateException{
    	HibernateCallback hc = new HibernateCallback() {
    		public Object doInHibernate(Session session) throws HibernateException {
    			return session.createQuery(
    			"from Category as cat where cat.id=?").
    			setLong(0, categoryId.longValue()).
    			uniqueResult();
    		}
    	};
    	return (Category) getHibernateTemplate().execute(hc);
    }
    
    public void updateCategory(final Category category) throws ConflictingCategoryNameException, StaleObjectModificationException{
    	HibernateCallback hc = new HibernateCallback() {
    		public Object doInHibernate(Session session) throws HibernateException {
    			session.evict(category);
    			Category persistentCat = (Category)session.load(Category.class, category.getId());
    			int numNameConflicts = ((Integer)session.createQuery(
    			"select count(ca) from Category as ca where ca.name = ? and ca.gradebook = ? and ca.id != ? and ca.removed=false").
    			setString(0, category.getName()).
    			setEntity(1, category.getGradebook()).
    			setLong(2, category.getId().longValue()).
    			uniqueResult()).intValue();
    			if(numNameConflicts > 0) {
    				throw new ConflictingCategoryNameException("You can not save multiple category in a gradebook with the same name");
    			}
    			session.evict(persistentCat);
    			session.update(category);
    			return null;
    		}
    	};
    	try {
    		getHibernateTemplate().execute(hc);
    	} catch (Exception e) {
    		throw new StaleObjectModificationException(e);
    	}
    }
    
    public void removeCategory(final Long categoryId) throws StaleObjectModificationException{
    	HibernateCallback hc = new HibernateCallback() {
    		public Object doInHibernate(Session session) throws HibernateException {
    			Category persistentCat = (Category)session.load(Category.class, categoryId);

    			List assigns = getAssignmentsForCategory(categoryId);
    			for(Iterator iter = assigns.iterator(); iter.hasNext();)
    			{
    				Assignment assignment = (Assignment) iter.next();
    				assignment.setCategory(null);
    				updateAssignment(assignment, session);
    			}

    			persistentCat.setRemoved(true);
    			session.update(persistentCat);
    			return null;
    		}
    	};
    	try {
    		getHibernateTemplate().execute(hc);
    	} catch (Exception e) {
    		throw new StaleObjectModificationException(e);
    	}
    }
}
