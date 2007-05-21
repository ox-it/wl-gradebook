/**********************************************************************************
 *
 * $Id$
 *
 ***********************************************************************************
 *
 * Copyright (c) 2005, 2006, 2007 The Regents of the University of California, The MIT Corporation
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

package org.sakaiproject.tool.gradebook.jsf;

import javax.faces.component.UIComponent;
import javax.faces.context.FacesContext;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.sakaiproject.tool.gradebook.ui.AssignmentGradeRow;
import org.sakaiproject.tool.gradebook.Assignment;
import org.sakaiproject.tool.gradebook.Category;
import org.sakaiproject.tool.gradebook.CourseGrade;
import org.sakaiproject.tool.gradebook.Gradebook;
import org.sakaiproject.service.gradebook.shared.GradebookService;

/**
 * This formatting-only converver displays appropriate class avg depending on
 * grade entry method for this gradebook. Truncates with 0 decimals.
 */
public class ClassAvgConverterBasicDisplay extends PointsConverter {
	private static final Log log = LogFactory.getLog(ClassAvgConverterBasicDisplay.class);

	public String getAsString(FacesContext context, UIComponent component, Object value) {
		if (log.isDebugEnabled()) log.debug("getAsString(" + context + ", " + component + ", " + value + ")");

		String formattedAvg;
		boolean isPoints = false;
		boolean isPercent = false;
		Object avg = null;
		Gradebook gradebook;

		if (value != null) {
			if (value instanceof Assignment) {
				Assignment assignment = (Assignment)value;
				gradebook = assignment.getGradebook();

				if (gradebook.getGrade_type() == GradebookService.GRADE_TYPE_POINTS) {
					isPoints = true;
					avg = assignment.getAverageTotal();
				} else if (gradebook.getGrade_type() == GradebookService.GRADE_TYPE_PERCENTAGE) {
					isPercent = true;
					avg = assignment.getMean();
				}
				
			} else if (value instanceof Category) {
				Category category = (Category) value;
				gradebook = category.getGradebook();
				
				// Unassigned category in weighted gb won't have avg
				if (category.getId() == null && gradebook.getCategory_type() == GradebookService.CATEGORY_TYPE_WEIGHTED_CATEGORY) {
					return FacesUtil.getLocalizedString("overview_unassigned_cat_avg");
				}
				isPercent = true;
				avg = category.getMean();
		
			} else if (value instanceof CourseGrade) {
				// course grade is always displayed as %
				isPercent = true;
				CourseGrade courseGrade = (CourseGrade) value;
				avg = courseGrade.getMean();	
				
			} else if (value instanceof AssignmentGradeRow) {
				AssignmentGradeRow gradeRow = (AssignmentGradeRow) value;
				gradebook = gradeRow.getGradebook();
				avg = gradeRow.getScore();
				if (gradebook.getGrade_type() == GradebookService.GRADE_TYPE_PERCENTAGE) {
					isPercent = true;
				}
			}
		}
		
		formattedAvg = getFormattedValue(context, component, avg);
		
		if (avg != null) {
			if (isPercent) {
				formattedAvg = FacesUtil.getLocalizedString("overview_avg_display_percent", new String[] {formattedAvg} );
			}
		}
		return formattedAvg;
	}
	
	private String getFormattedValue(FacesContext context, UIComponent component, Object value) {
		String formattedValue;
		if (value == null) {
			formattedValue = FacesUtil.getLocalizedString("score_null_placeholder");
		} else {
			if (value instanceof Number) {
				// Truncate to 0 decimal places.
				value = new Double(FacesUtil.getRoundDown(((Number)value).doubleValue(), 0));
			}
			formattedValue = super.getAsString(context, component, value);
		}
		
		return formattedValue;
	}
}
