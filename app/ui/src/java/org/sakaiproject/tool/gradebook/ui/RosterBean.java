/**********************************************************************************
*
* $Id$
*
***********************************************************************************
*
* Copyright (c) 2005 The Regents of the University of California, The MIT Corporation
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

package org.sakaiproject.tool.gradebook.ui;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.text.NumberFormat;

import javax.faces.application.Application;
import javax.faces.component.UIColumn;
import javax.faces.component.html.HtmlOutputText;
import javax.faces.component.UIComponent;
import javax.faces.component.html.HtmlPanelGroup;
import javax.faces.component.html.HtmlCommandLink;
import javax.faces.component.UIParameter;
import javax.faces.context.ExternalContext;
import javax.faces.context.FacesContext;
import javax.faces.event.ActionEvent;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.myfaces.component.html.ext.HtmlDataTable;
import org.apache.myfaces.custom.sortheader.HtmlCommandSortHeader;
import org.sakaiproject.jsf.spreadsheet.SpreadsheetDataFileWriterCsv;
import org.sakaiproject.jsf.spreadsheet.SpreadsheetDataFileWriterXls;
import org.sakaiproject.jsf.spreadsheet.SpreadsheetUtil;
import org.sakaiproject.section.api.coursemanagement.EnrollmentRecord;
import org.sakaiproject.section.api.coursemanagement.User;
import org.sakaiproject.tool.gradebook.AbstractGradeRecord;
import org.sakaiproject.tool.gradebook.Assignment;
import org.sakaiproject.tool.gradebook.Category;
import org.sakaiproject.tool.gradebook.CourseGrade;
import org.sakaiproject.tool.gradebook.CourseGradeRecord;
import org.sakaiproject.tool.gradebook.GradableObject;
import org.sakaiproject.tool.gradebook.jsf.AssignmentPointsConverter;
import org.sakaiproject.tool.gradebook.jsf.CategoryPointsConverter;
import org.sakaiproject.tool.gradebook.jsf.FacesUtil;

/**
 * Backing bean for the visible list of assignments in the gradebook.
 */
public class RosterBean extends EnrollmentTableBean implements Serializable, Paging {
	private static final Log logger = LogFactory.getLog(RosterBean.class);

	// Used to generate IDs for the dynamically created assignment columns.
	private static final String ASSIGNMENT_COLUMN_PREFIX = "asg_";

	// View maintenance fields - serializable.
	private List gradableObjectColumns;	// Needed to build table columns
    private List workingEnrollments;
    
    private CourseGrade avgCourseGrade;
    
    private HtmlDataTable originalRosterDataTable = null;

    public class GradableObjectColumn implements Serializable {
		private Long id;
		private String name;
		private Boolean categoryColumn = false;
		private Boolean assignmentColumn = false;
		private Long assignmentId;
		private Boolean inactive = false;

		public GradableObjectColumn() {
		}
		public GradableObjectColumn(GradableObject gradableObject) {
			id = gradableObject.getId();
			name = getColumnHeader(gradableObject);
			categoryColumn = false;
			assignmentId = getColumnHeaderAssignmentId(gradableObject);
			assignmentColumn = !gradableObject.isCourseGrade();
			inactive = (!gradableObject.isCourseGrade() && !((Assignment)gradableObject).isReleased() ? true : false);
			if (1 == 1){ 
				inactive = inactive;
			}
		}

		public Long getId() {
			return id;
		}
		public void setId(Long id) {
			this.id = id;
		}
		public String getName() {
			return name;
		}
		public void setName(String name) {
			this.name = name;
		}
		public Boolean getCategoryColumn() {
			return categoryColumn;
		}
		public void setCategoryColumn(Boolean categoryColumn){
			this.categoryColumn = categoryColumn;
		}
		public Long getAssignmentId() {
			return assignmentId;
		}
		public void setAssignmentId(Long assignmentId) {
			this.assignmentId = assignmentId;
		}
		public Boolean getAssignmentColumn() {
			return assignmentColumn;
		}
		public void setAssignmentColumn(Boolean assignmentColumn) {
			this.assignmentColumn = assignmentColumn;
		}
		public Boolean getInactive() {
			return this.inactive;
		}
		public void setInactive(Boolean inactive) {
			this.inactive = inactive;
		}
	}

	// Controller fields - transient.
	private transient List studentRows;
	private transient Map gradeRecordMap;
	private transient Map categoryResultMap;

	public class StudentRow implements Serializable {
        private EnrollmentRecord enrollment;

		public StudentRow() {
		}
		public StudentRow(EnrollmentRecord enrollment) {
            this.enrollment = enrollment;
		}

		public String getStudentUid() {
			return enrollment.getUser().getUserUid();
		}
		public String getSortName() {
			return enrollment.getUser().getSortName();
		}
		public String getDisplayId() {
			return enrollment.getUser().getDisplayId();
		}

		public Map getScores() {
			return (Map)gradeRecordMap.get(enrollment.getUser().getUserUid());
		}
		
		public Map getCategoryResults() {
			return (Map)categoryResultMap.get(enrollment.getUser().getUserUid());
		}
	}

	protected void init() {
		super.init();
		//get array to hold columns
		gradableObjectColumns = new ArrayList();
		
		avgCourseGrade = new CourseGrade();
		
		//get the selected categoryUID 
		String selectedCategoryUid = getSelectedCategoryUid();

		CourseGrade courseGrade = getGradebookManager().getCourseGrade(getGradebookId());
		//first add Cumulative if not a selected category
		if(selectedCategoryUid == null){
			gradableObjectColumns.add(new GradableObjectColumn(courseGrade));
		}
		
		
		//next get all of the categories
		List categories = getGradebookManager().getCategoriesWithStats(getGradebookId(),Assignment.DEFAULT_SORT, true, Category.SORT_BY_NAME, true);
		int categoryCount = categories.size();
		
		for (Iterator iter = categories.iterator(); iter.hasNext(); ){
			Object obj = iter.next();
			if(!(obj instanceof Category)){
				if(obj instanceof CourseGrade){
					avgCourseGrade = (CourseGrade) obj;
				}
				continue;
			}
			Category cat = (Category) obj;

			if(selectedCategoryUid == null || selectedCategoryUid.equals(cat.getId().toString())){
			
				//get the category column
				GradableObjectColumn categoryColumn = new GradableObjectColumn();
				String name = cat.getName();
				if(getWeightingEnabled()){
					//if weighting is enabled, then add "(weight)" to column
					Double value = (Double) ((Number)cat.getWeight());
					name = name + " (" +  NumberFormat.getNumberInstance().format(value * 100.0) + "%)";
					//name = name + " (" + Integer.toString(cat.getWeight() * 100) + "%)";
				}
				categoryColumn.setName(name);
				categoryColumn.setId(cat.getId());
				categoryColumn.setCategoryColumn(true);
				
				//if selectedCategoryUID, then we want the category first, otherwise after
				if(selectedCategoryUid != null) {
					gradableObjectColumns.add(categoryColumn);
				}
				
				//add assignments
				List assignments = getGradebookManager().getAssignmentsForCategory(cat.getId());
				for (Iterator assignmentsIter = assignments.iterator(); assignmentsIter.hasNext();){
					gradableObjectColumns.add(new GradableObjectColumn((GradableObject)assignmentsIter.next()));
				}
				//if not selectedCategoryUID, then add category field after
				if(selectedCategoryUid == null) {
					gradableObjectColumns.add(categoryColumn);
				}
			}
		}
		if(selectedCategoryUid == null){
			//get Assignments with no category
			List unassignedAssignments = getGradebookManager().getAssignmentsWithNoCategory(getGradebookId(), Assignment.DEFAULT_SORT, true);
			int unassignedAssignmentCount = unassignedAssignments.size();
			for (Iterator assignmentsIter = unassignedAssignments.iterator(); assignmentsIter.hasNext(); ){
				gradableObjectColumns.add(new GradableObjectColumn((GradableObject) assignmentsIter.next()));
			}
			//If there are categories and there are unassigned assignments, then display Unassigned Category column
			if (getCategoriesEnabled() && unassignedAssignmentCount > 0){
				//add Unassigned column
				GradableObjectColumn unassignedCategoryColumn = new GradableObjectColumn();
				unassignedCategoryColumn.setName("Unassigned");
				unassignedCategoryColumn.setCategoryColumn(true);
				gradableObjectColumns.add(unassignedCategoryColumn);
			}
		}
		
        Map enrollmentMap = getOrderedEnrollmentMap();

		List gradeRecords = getGradebookManager().getAllAssignmentGradeRecords(getGradebookId(), enrollmentMap.keySet());
        workingEnrollments = new ArrayList(enrollmentMap.values());

        gradeRecordMap = new HashMap();
        getGradebookManager().addToGradeRecordMap(gradeRecordMap, gradeRecords);
		if (logger.isDebugEnabled()) logger.debug("init - gradeRecordMap.keySet().size() = " + gradeRecordMap.keySet().size());

		List assignments = null;
		if(selectedCategoryUid == null) {
			assignments = getGradebookManager().getAssignments(getGradebookId());
		} else {
			assignments = getGradebookManager().getAssignmentsForCategory(getSelectedSectionFilterValue().longValue());
		}
			
		List courseGradeRecords = getGradebookManager().getPointsEarnedCourseGradeRecords(courseGrade, enrollmentMap.keySet(), assignments, gradeRecordMap);
		Collections.sort(courseGradeRecords, CourseGradeRecord.calcComparator);
        getGradebookManager().addToGradeRecordMap(gradeRecordMap, courseGradeRecords);
        gradeRecords.addAll(courseGradeRecords);
        
        //do category results
        categoryResultMap = new HashMap();
        getGradebookManager().addToCategoryResultMap(categoryResultMap, categories, gradeRecordMap, enrollmentMap);
        if (logger.isDebugEnabled()) logger.debug("init - categoryResultMap.keySet().size() = " + categoryResultMap.keySet().size());

        
        
        if (!isEnrollmentSort()) {
        	// Need to sort and page based on a scores column.
        	String sortColumn = getSortColumn();
        	List scoreSortedEnrollments = new ArrayList();
			for(Iterator iter = gradeRecords.iterator(); iter.hasNext();) {
				AbstractGradeRecord agr = (AbstractGradeRecord)iter.next();
				if(getColumnHeader(agr.getGradableObject()).equals(sortColumn)) {
					scoreSortedEnrollments.add(enrollmentMap.get(agr.getStudentId()));
				}
			}

            // Put enrollments with no scores at the beginning of the final list.
            workingEnrollments.removeAll(scoreSortedEnrollments);

            // Add all sorted enrollments with scores into the final list
            workingEnrollments.addAll(scoreSortedEnrollments);

            workingEnrollments = finalizeSortingAndPaging(workingEnrollments);
		}

		studentRows = new ArrayList(workingEnrollments.size());
        for (Iterator iter = workingEnrollments.iterator(); iter.hasNext(); ) {
            EnrollmentRecord enrollment = (EnrollmentRecord)iter.next();
            studentRows.add(new StudentRow(enrollment));
        }

	}
	
	private String getColumnHeader(GradableObject gradableObject) {
		if (gradableObject.isCourseGrade()) {
			return getLocalizedString("roster_course_grade_column_name");
		} else {
			return ((Assignment)gradableObject).getName();
		}
	}
	
	private Long getColumnHeaderAssignmentId(GradableObject gradableObject) {
		if (gradableObject.isCourseGrade()) {
			return new Long(-1);
		} else {
			return ((Assignment)gradableObject).getId();
		}
	}

	// The roster table uses assignments as columns, and therefore the component
	// model needs to have those columns added dynamically, based on the current
	// state of the gradebook.
	// In JSF 1.1, dynamic data table columns are managed by binding the component
	// tag to a bean property.

	// It's not exactly intuitive, but the convention is for the bean to return
	// null, so that JSF can create and manage the UIData component itself.
	public HtmlDataTable getRosterDataTable() {
		if (logger.isDebugEnabled()) logger.debug("getRosterDataTable");
		return null;
	}

	public void setRosterDataTable(HtmlDataTable rosterDataTable) {
		if (logger.isDebugEnabled()) {
			logger.debug("setRosterDataTable gradableObjectColumns=" + gradableObjectColumns + ", rosterDataTable=" + rosterDataTable);
			if (rosterDataTable != null) {
				logger.debug("  data children=" + rosterDataTable.getChildren());
			}
		}
		
		//check if columns of changed due to categories
		ExternalContext context = FacesContext.getCurrentInstance().getExternalContext();
		Map paramMap = context.getRequestParameterMap();
		String catId = (String) paramMap.get("gbForm:selectCategoryFilter");
		//due to this set method getting called before all others, including the setSelectCategoryFilterValue, 
		// we have to manually set the value, then call init to get the new gradableObjectColumns array
		if(catId != null && !catId.equals(getSelectedCategoryUid())) {
			this.setSelectedCategoryFilterValue(new Integer(catId));
			init();
			//now destroy all of the columns to be readded
			rosterDataTable.getChildren().removeAll( rosterDataTable.getChildren().subList(2, rosterDataTable.getChildren().size()));
		}
    

        // Set the columnClasses on the data table
        StringBuffer colClasses = new StringBuffer("left,left,");
        for(Iterator iter = gradableObjectColumns.iterator(); iter.hasNext();) {
        	iter.next();
            colClasses.append("center");
            if(iter.hasNext()) {
                colClasses.append(",");
            }
        }
        rosterDataTable.setColumnClasses(colClasses.toString());

		if (rosterDataTable.findComponent(ASSIGNMENT_COLUMN_PREFIX + "0") == null) {
			Application app = FacesContext.getCurrentInstance().getApplication();

			// Add columns for each assignment. Be sure to create unique IDs
			// for all child components.
			int colpos = 0;
			for (Iterator iter = gradableObjectColumns.iterator(); iter.hasNext(); colpos++) {
				GradableObjectColumn columnData = (GradableObjectColumn)iter.next();

				UIColumn col = new UIColumn();
				col.setId(ASSIGNMENT_COLUMN_PREFIX + colpos);

				if(!columnData.getCategoryColumn()){
	                HtmlCommandSortHeader sortHeader = new HtmlCommandSortHeader();
	                sortHeader.setId(ASSIGNMENT_COLUMN_PREFIX + "sorthdr_" + colpos);
	                sortHeader.setRendererType("org.apache.myfaces.SortHeader");	// Yes, this is necessary.
	                sortHeader.setArrow(true);
	                sortHeader.setColumnName(columnData.getName());
	                sortHeader.setActionListener(app.createMethodBinding("#{rosterBean.sort}", new Class[] {ActionEvent.class}));
	                // Allow word-wrapping on assignment name columns.
	                if(columnData.getInactive()){
	                	sortHeader.setStyleClass("inactive-column allowWrap");
	                } else {
	                	sortHeader.setStyleClass("allowWrap");
	                }
	
					HtmlOutputText headerText = new HtmlOutputText();
					headerText.setId(ASSIGNMENT_COLUMN_PREFIX + "hdr_" + colpos);
					// Try straight setValue rather than setValueBinding.
					headerText.setValue(columnData.getName());
	
	                sortHeader.getChildren().add(headerText);
	                
	                if(columnData.getAssignmentColumn()){
		                //<h:commandLink action="assignmentDetails">
						//	<h:outputText value="Details" />
						//	<f:param name="assignmentId" value="#{gradableObject.id}"/>
		                //</h:commandLink>
		                
		                //get details link
		                HtmlCommandLink detailsLink = new HtmlCommandLink();
		                detailsLink.setAction(app.createMethodBinding("#{rosterBean.assignmentDetails}", new Class[] {}));
		                detailsLink.setId(ASSIGNMENT_COLUMN_PREFIX + "hdr_link_" + colpos);
		                HtmlOutputText detailsText = new HtmlOutputText();
		                detailsText.setId(ASSIGNMENT_COLUMN_PREFIX + "hdr_details_" + colpos);
		                detailsText.setValue("<em>Details</em>");
		                detailsText.setEscape(false);
		                detailsText.setStyle("font-size: 80%");
		                detailsLink.getChildren().add(detailsText);
		                
		                UIParameter param = new UIParameter();
		                param.setName("assignmentId");
		                param.setValue(columnData.getAssignmentId());
		                detailsLink.getChildren().add(param);
		                
		                UIParameter param2 = new UIParameter();
		                param2.setName("breadcrumbPage");
		                param2.setValue("roster");
		                detailsLink.getChildren().add(param2);
		                
		                HtmlOutputText br = new HtmlOutputText();
		                br.setValue("<br />");
		                br.setEscape(false);
		                
		                //make a panel group to add link 
		                HtmlPanelGroup pg = new HtmlPanelGroup();
		                pg.getChildren().add(sortHeader);
		                pg.getChildren().add(br);
		                pg.getChildren().add(detailsLink);
		                
		                col.setHeader(pg);
	                } else {
	                	col.setHeader(sortHeader);	
	                }
				} else {
					//if we are dealing with a category
					HtmlOutputText headerText = new HtmlOutputText();
					headerText.setId(ASSIGNMENT_COLUMN_PREFIX + "hrd_" + colpos);
					headerText.setValue(columnData.getName());
					
					col.setHeader(headerText);
				}

				HtmlOutputText contents = new HtmlOutputText();
				contents.setEscape(false);
				contents.setId(ASSIGNMENT_COLUMN_PREFIX + "cell_" + colpos);
				if(!columnData.getCategoryColumn()){
					contents.setValueBinding("value",
							app.createValueBinding("#{row.scores[rosterBean.gradableObjectColumns[" + colpos + "].id]}"));
					contents.setConverter(new AssignmentPointsConverter());
				} else {
					contents.setValueBinding("value",
							app.createValueBinding("#{row.categoryResults[rosterBean.gradableObjectColumns[" + colpos + "].id]}"));
					contents.setConverter(new CategoryPointsConverter());
				}
                

                // Distinguish the "Cumulative" score for the course, which, by convention,
                // is always the first column.
                if (colpos == 0) {
                	contents.setStyleClass("courseGrade center");
                }

				col.getChildren().add(contents);

				rosterDataTable.getChildren().add(col);
			}
		}
	}

	public List getGradableObjectColumns() {
		return gradableObjectColumns;
	}
	public void setGradableObjectColumns(List gradableObjectColumns) {
		this.gradableObjectColumns = gradableObjectColumns;
	}

	public List getStudentRows() {
		return studentRows;
	}

	// Sorting
    public boolean isSortAscending() {
        return getPreferencesBean().isRosterTableSortAscending();
    }
    public void setSortAscending(boolean sortAscending) {
        getPreferencesBean().setRosterTableSortAscending(sortAscending);
    }
    public String getSortColumn() {
        return getPreferencesBean().getRosterTableSortColumn();
    }
    public void setSortColumn(String sortColumn) {
        getPreferencesBean().setRosterTableSortColumn(sortColumn);
    }
    
    public CourseGrade getAvgCourseGrade() {
		return avgCourseGrade;
	}
	public void setAvgCourseGrade(CourseGrade courseGrade) {
		this.avgCourseGrade = courseGrade;
	}
    
    public String getAvgCourseGradeLetter() {
		String letterGrade = "";
		if (avgCourseGrade != null) {
			letterGrade = getGradebook().getSelectedGradeMapping().getGrade(avgCourseGrade.getMean());
		}
		
		if (letterGrade == null || letterGrade.trim().length() < 1) {
			letterGrade = getLocalizedString("score_null_placeholder");
		}
		
		return letterGrade;
	}

    public void exportCsv(ActionEvent event){
        if(logger.isInfoEnabled()) logger.info("exporting roster as CSV for gradebook " + getGradebookUid());
        getGradebookBean().getEventTrackingService().postEvent("gradebook.downloadRoster","/gradebook/"+getGradebookId()+"/"+getAuthzLevel());
        SpreadsheetUtil.downloadSpreadsheetData(getSpreadsheetData(), 
        		getDownloadFileName(getLocalizedString("export_gradebook_prefix")), 
        		new SpreadsheetDataFileWriterCsv());
    }

    public void exportExcel(ActionEvent event){
        if(logger.isInfoEnabled()) logger.info("exporting roster as Excel for gradebook " + getGradebookUid());
        String authzLevel = (getGradebookBean().getAuthzService().isUserAbleToGradeAll(getGradebookUid())) ?"instructor" : "TA";
        getGradebookBean().getEventTrackingService().postEvent("gradebook.downloadRoster","/gradebook/"+getGradebookId()+"/"+getAuthzLevel());
        SpreadsheetUtil.downloadSpreadsheetData(getSpreadsheetData(), 
        		getDownloadFileName(getLocalizedString("export_gradebook_prefix")), 
        		new SpreadsheetDataFileWriterXls());
    }
    
    private List<List<Object>> getSpreadsheetData() {
    	// Get the full list of filtered enrollments and scores (not just the current page's worth).
    	List filteredEnrollments = getWorkingEnrollments();
    	Collections.sort(filteredEnrollments, ENROLLMENT_NAME_COMPARATOR);
    	Set<String> studentUids = new HashSet<String>();
    	for (Iterator iter = filteredEnrollments.iterator(); iter.hasNext(); ) {
    		EnrollmentRecord enrollment = (EnrollmentRecord)iter.next();
    		studentUids.add(enrollment.getUser().getUserUid());
    	}

		Map filteredGradesMap = new HashMap();
    	List gradeRecords = getGradebookManager().getAllAssignmentGradeRecords(getGradebookId(), studentUids);
        getGradebookManager().addToGradeRecordMap(filteredGradesMap, gradeRecords);
        
		List gradableObjects = getGradebookManager().getAssignments(getGradebookId());
		CourseGrade courseGrade = getGradebookManager().getCourseGrade(getGradebookId());
		List courseGradeRecords = getGradebookManager().getPointsEarnedCourseGradeRecords(courseGrade, studentUids, gradableObjects, filteredGradesMap);
        getGradebookManager().addToGradeRecordMap(filteredGradesMap, courseGradeRecords);
        gradableObjects.add(courseGrade);
    	return getSpreadsheetData(filteredEnrollments, filteredGradesMap, gradableObjects);
    }
 
    /**
     * Creates the actual 'spreadsheet' List needed from gradebook objects
     * Format:
     * 	Header Row: Student id, Student Name, Assignment(s)
     *  Points Possible Row
     *  Student Rows
     * 
     * @param enrollments
     * @param gradesMap
     * @param gradableObjects
     * @return
     */
    private List<List<Object>> getSpreadsheetData(List enrollments, Map gradesMap, List gradableObjects) {
    	List<List<Object>> spreadsheetData = new ArrayList<List<Object>>();

    	// Build column headers and points possible rows.
        List<Object> headerRow = new ArrayList<Object>();
        List<Object> pointsPossibleRow = new ArrayList<Object>();
        
        headerRow.add(getLocalizedString("export_student_id"));
        headerRow.add(getLocalizedString("export_student_name"));
        
        // Student id and name rows have blank points possible
        pointsPossibleRow.add(getLocalizedString("export_points_possible"));
        pointsPossibleRow.add("");
        
        for (Object gradableObject : gradableObjects) {
        	String colName = null;
        	Double ptsPossible = 0.0;

        	if (gradableObject instanceof Assignment) {
         		colName = ((Assignment)gradableObject).getName();
         		ptsPossible = new Double(((Assignment) gradableObject).getPointsPossible());
         	} else if (gradableObject instanceof CourseGrade) {
         		colName = getLocalizedString("roster_course_grade_column_name");
         	}

         	headerRow.add(colName);
        	pointsPossibleRow.add(ptsPossible);
        }
        spreadsheetData.add(headerRow);
        spreadsheetData.add(pointsPossibleRow);

        // Build student score rows.
        for (Object enrollment : enrollments) {
        	User student = ((EnrollmentRecord)enrollment).getUser();
        	String studentUid = student.getUserUid();
        	Map studentMap = (Map)gradesMap.get(studentUid);
        	List<Object> row = new ArrayList<Object>();
        	row.add(student.getDisplayId());
        	row.add(student.getSortName());
        	for (Object gradableObject : gradableObjects) {
        		Double score = null;
        		if (studentMap != null) {
        			AbstractGradeRecord gradeRecord = (AbstractGradeRecord)studentMap.get(((GradableObject)gradableObject).getId()); 
        			if (gradeRecord != null) {
        				score = gradeRecord.getPointsEarned();
        			}
        		}
    			row.add(score);
        	}
        	spreadsheetData.add(row);
        }
    	
    	return spreadsheetData;
    }
    
    public String assignmentDetails(){
    	return "assignmentDetails";
    }
}
