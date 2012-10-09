package org.saiku.service.olap;

import com.alipay.higo.commons.searcher.QueryBean;
import com.alipay.higo.commons.searcher.QueryBusyException;
import com.alipay.higo.commons.searcher.QuerysBean;
import com.alipay.higo.commons.searcher.condition.Condition;
import com.alipay.higo.commons.searcher.condition.ConditionBean;
import com.alipay.higo.commons.searcher.http.impl.SimpleFetcher;
import com.alipay.higo.commons.searcher.tuning.QueryScheduler;
import com.alipay.higo.node.util.ConstantUtil;

import org.olap4j.Axis;
import org.olap4j.OlapException;
import org.olap4j.metadata.Dimension;
import org.olap4j.metadata.Level;
import org.olap4j.metadata.Member;
import org.olap4j.metadata.MetadataElement;
import org.olap4j.query.QueryAxis;
import org.olap4j.query.QueryDimension;
import org.olap4j.query.Selection;
import org.saiku.olap.dto.resultset.AbstractBaseCell;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.dto.resultset.DataCell;
import org.saiku.olap.dto.resultset.MemberCell;
import org.saiku.olap.query.IQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeoutException;

public class HigoQueryService implements Serializable {

    private static final Logger log = LoggerFactory.getLogger(HigoQueryService.class);

    private SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T00:00:00Z'");

    public HigoQueryService() {
    }

    /**
     * HIGO临时处理方式
     *
     *
     * @param query
     * @param queryScheduler
     * @return
     */
    public CellDataSet higo_execute(IQuery query, QueryScheduler queryScheduler) {

        CellDataSet result = null;
        try {
            //所有为使用的Measure，当用户只选择纬度时，默认使用第一个measure值。
            String defaultMeasure=null;
            String report_date=null;
            //获取未使用维度设置为对应dimension=-1
            QueryAxis unUsedAxis= query.getUnusedAxis();
            //去除时间和Measure纬未使用的纬度
            List<String>unUsedDimList=new ArrayList<String>();
            List<QueryDimension>unUsedDimensionListAll=unUsedAxis.getDimensions();
            for(QueryDimension d:unUsedDimensionListAll){
               if(d.getDimension().getDimensionType().ordinal()==2){
                  //Measure Dimension
                  //TODO 需空指针判断
                 List<Member>unUsedMember=d.getDimension().getDefaultHierarchy().getLevels().get(0).getMembers();
                 defaultMeasure=unUsedMember.get(0).getName();
                 continue;
               }
               if(d.getName().equals("DIM_TIME")){
                 //当用户未指定时间纬过滤时，默认指定当前日期
                   report_date=f.format(new Date());
                   continue;
               }
                unUsedDimList.add(d.getDimension().getDescription());
            }


            //过滤维度,暂时只允许时间纬度
            QueryAxis filterAxis = query.getAxis(Axis.FILTER);
            List<QueryDimension> filterDimList = filterAxis.getDimensions();
            for (QueryDimension filterDim : filterDimList) {
                if(filterDim.getName().equals("DIM_TIME")){
                    List<Selection> filterSelectionList = filterDim.getInclusions();
                    List<Member> filterList = fetchMemberListBySelection(filterSelectionList);
                    for(Member m:filterList){
                        String name=m.getName();
                        report_date=name.substring(0,4)+"-"+name.substring(4,6)+"-"+name.substring(6,8)+"T00:00:00Z";
                    }
                }
            }

            //列维度
            QueryAxis columnAxis = query.getAxis(Axis.COLUMNS);
            List<QueryDimension> columnDimList = columnAxis.getDimensions();
            //行维度
            QueryAxis rowAxis = query.getAxis(Axis.ROWS);
            List<QueryDimension> rowDimList = rowAxis.getDimensions();

            int offset = columnDimList.size();
            int rowoffset = rowDimList.size();
            Map<List<Integer>, AbstractBaseCell> cellMap = new HashMap<List<Integer>, AbstractBaseCell>();
            //列维度处理
            //存储列维度笛卡尔积之后集合
            List<List<String[]>> columnDimValList = new ArrayList<List<String[]>>();
            for (int i = 0; i < columnDimList.size(); i++) {
                QueryDimension columnDim = columnDimList.get(i);
                List<Selection> columnSelectionList = columnDim.getInclusions();
                Dimension dim = columnDim.getDimension();

                Dimension.Type type = dim.getDimensionType();

                String dim_desc = dim.getDescription();
                String dim_caption = dim.getCaption();
                List<String[]> columnPerDimValList = new ArrayList<String[]>();

                //获取Member List
                List<Member> memberlist = fetchMemberListBySelection(columnSelectionList);
                for (int k = 0; k < memberlist.size(); k++) {
                    Member member = memberlist.get(k);
                    //dim_val[0]表示维度查询条件for Higo 例如 dimension1=a，存储dimension1；
                    //dim_val[1]表示维度查询条件值 for higo，例如同上a
                    //dim_val[2]表示对应页面显示标题
                    //dim_val[3]表示纬度类型，用于判断measure
                    String[] dim_val = {dim_desc==null?member.getDescription():dim_desc, member.getName(), member.getCaption(), String.valueOf(type.ordinal())};
                    columnPerDimValList.add(dim_val);
                }
                columnDimValList.add(columnPerDimValList);
            }

            //行维度处理
            //存储行维度笛卡尔积之后集合
            List<List<String[]>> rowDimValList = new ArrayList<List<String[]>>();
            for (int i = 0; i < rowDimList.size(); i++) {
                QueryDimension rowDim = rowDimList.get(i);

                Dimension dim = rowDim.getDimension();
                Dimension.Type type = dim.getDimensionType();
                String dim_desc = dim.getDescription();
                String dim_caption = dim.getCaption();

                //MemberCell for Dimension Caption header
                MemberCell membercell = new MemberCell(false, false);
                membercell.setRawValue(dim_caption);
                membercell.setFormattedValue(dim_caption);
                membercell.setProperty("__headertype", "row_header_header");
                membercell.setProperty("levelindex", String.valueOf(i));
                List<Integer> member_coordinates = new ArrayList<Integer>();
                member_coordinates.add(offset - 1);
                member_coordinates.add(i);
                cellMap.put(member_coordinates, membercell);

                List<String[]> rowPerDimValList = new ArrayList<String[]>();
                List<Selection> rowSelectionList = rowDim.getInclusions();


                //获取Member List
                List<Member> memberlist = fetchMemberListBySelection(rowSelectionList);

                for (int j = 0; j < memberlist.size(); j++) {
                    Member member = memberlist.get(j);
                    String caption = member.getCaption();
                    //dim_val[0]表示维度查询条件for Higo 例如 dimension1=a，存储dimension1；
                    //dim_val[1]表示维度查询条件值 for higo，例如同上a
                    //dim_val[2]表示对应页面显示标题
                    //dim_val[3]表示纬度类型，用于判断measure
                    String[] dim_val = {dim_desc==null?member.getDescription():dim_desc, member.getName(), member.getCaption(), String.valueOf(type.ordinal())};
                    rowPerDimValList.add(dim_val);
                }
                rowDimValList.add(rowPerDimValList);
            }
            int height = 0;
            int width = 0;
            //列维度笛卡尔积
            List<List<String[]>> column_dikaerji = dikaerji(columnDimValList);
            width = column_dikaerji.size() + rowDimList.size();
            for (int i = 0; i < column_dikaerji.size(); i++) {
                List<String[]> sublist = column_dikaerji.get(i);
                for (int j = 0; j < sublist.size(); j++) {
                    String[] membercell = sublist.get(j);
                    MemberCell submembercell = new MemberCell(false, false);
                    submembercell.setRawValue(membercell[2]);
                    submembercell.setFormattedValue(membercell[2]);
                    List<Integer> sub_member_coordinates = Arrays.asList(j, i + rowoffset);
                    cellMap.put(sub_member_coordinates, submembercell);
                }
            }
            //行维度笛卡尔积
            List<List<String[]>> row_dikaerji = dikaerji(rowDimValList);
            height = row_dikaerji.size() + offset;
            for (int i = 0; i < row_dikaerji.size(); i++) {
                List<String[]> sublist = row_dikaerji.get(i);
                for (int j = 0; j < sublist.size(); j++) {
                    String[] membercell = sublist.get(j);
                    MemberCell submembercell = new MemberCell(false, false);
                    submembercell.setRawValue(membercell[2]);
                    submembercell.setFormattedValue(membercell[2]);
                    cellMap.put(Arrays.asList(offset + i, j), submembercell);
                }
            }
            Long start = (new Date()).getTime();
            //行列笛卡尔积组合查询条件
            QuerysBean querysBean = new QuerysBean();
            for(int i=0;i<row_dikaerji.size();i++){
                //如条件中不包括measure值，则使用默认值。
                String measure=null;

                List<String[]>rows=row_dikaerji.get(i);

                for(int j=0;j<column_dikaerji.size();j++){
                    ConditionBean bean = new ConditionBean();
                    bean.addNormalQuery(Condition.LOGICAL_MUST, "table_id", "D2");
                    if(report_date!=null){
                        bean.addNormalQuery(Condition.LOGICAL_MUST, "report_date",report_date);
                    }
                    for(String[] m:rows){
                        if(m[3].equals("2")){
                            measure=m[0];
                            continue;
                        }
                        bean.addNormalQuery(Condition.LOGICAL_MUST, m[0], m[1]);
                    }

                    for(String[] n:column_dikaerji.get(j)){
                        if(n[3].equals("2")){
                            measure=n[0];
                            continue;
                        }
                        bean.addNormalQuery(Condition.LOGICAL_MUST, n[0], n[1]);
                    }
                    //未使用的纬度值设为-1；
                    for(String unUsedDim:unUsedDimList){
                        bean.addNormalQuery(Condition.LOGICAL_MUST, unUsedDim, "-1");
                    }
                    String queryTerm=null;
                    if(measure!=null){
                        queryTerm = Condition.query(bean, 0, 10, measure, measure);
                    }else{
                        queryTerm = Condition.query(bean, 0, 10, defaultMeasure, defaultMeasure);
                    }
                    querysBean.add(i,j, queryTerm, null);
                }
            }
            //TODO test为用户名
            querysBean = queryScheduler.query("test", querysBean);
            Map<String,QueryBean>resultMap=querysBean.getQueryBeans();
            if(resultMap!=null){
                for(String key:resultMap.keySet()){
                    String[]row_col=key.split("-");
                    QueryBean qb=resultMap.get(key);
                    String value= qb.getSimpleSumResult();
                    DataCell dataCell = new DataCell();
                    dataCell.setRawNumber(Double.valueOf(value));
                    dataCell.setFormattedValue(value);
                    int row= Integer.valueOf(row_col[0]);
                    int col=Integer.valueOf(row_col[1]);
                    dataCell.setCoordinates(Arrays.asList(row, col));
                    cellMap.put(Arrays.asList(col+offset,row+rowoffset),dataCell);
                }
            }
            Long end = (new Date()).getTime();
            //填充左上角空缺
            for (int i = 0; i < offset - 1; i++) {
                for (int j = 0; j < rowoffset; j++) {
                    cellMap.put(Arrays.asList(i, j), new MemberCell(false, false));
                }
            }

            AbstractBaseCell[][] headervalues = new AbstractBaseCell[columnDimList.size()][width];

            for (int y = 0; y < offset; y++) {
                for (int x = 0; x < width; x++) {
                    AbstractBaseCell cell = cellMap.get(Arrays.asList(y, x));
                    headervalues[y][x] = cell;

                }
            }

            AbstractBaseCell[][] bodyvalues = new AbstractBaseCell[height - offset][width];

            int z = 0;

            for (int y = offset; y < height; y++) {

                for (int x = 0; x < width; x++) {
                    AbstractBaseCell cell = cellMap.get(Arrays.asList(y, x));
                    if (cell == null) {
                        DataCell dataCell = new DataCell();
                        dataCell.setRawNumber(0.0);
                        dataCell.setFormattedValue("无");
                        dataCell.setCoordinates(Arrays.asList(y - offset, x - rowoffset));
                        bodyvalues[z][x] = dataCell;
                    } else {
                        bodyvalues[z][x] = cell;
                    }

                }
                z++;
            }


            result = new CellDataSet(width, height);
            result.setCellSetHeaders(headervalues);
            result.setCellSetBody(bodyvalues);
            result.setOffset(offset);
            result.setRuntime(new Double(end-start).intValue());
        } catch (OlapException e) {
            log.error("getDimensionType Exception", e);
        } catch (QueryBusyException e) {
            log.error("QueryBusyException Exception", e);
        } catch (TimeoutException e) {
            log.error("TimeoutException Exception", e);
        } catch (SimpleFetcher.ExceptionWithUser exceptionWithUser) {
            log.error("SimpleFetcher.ExceptionWithUser Exception", exceptionWithUser);
        }
        return result;
    }

    /**
     * 根据Selection列表获取Member集合
     *
     * @param selectionList
     * @return
     * @throws org.olap4j.OlapException
     */
    List<Member> fetchMemberListBySelection(List<Selection> selectionList) throws OlapException {
        List<Member> memberlist = new ArrayList<Member>();
        for (int j = 0; j < selectionList.size(); j++) {
            Selection columnSelection = selectionList.get(j);
            MetadataElement me = columnSelection.getRootElement();
            if (me instanceof Level) {
                Level level = (Level) me;
                memberlist.addAll(level.getMembers());

            } else if (me instanceof Member) {
                Member member = (Member) me;
                memberlist.add(member);
            }
        }
        return memberlist;
    }

    /**
     * 笛卡尔积操作
     *
     * @param listall 对集合列表进行笛卡尔积操作。
     * @return
     */
    List<List<String[]>> dikaerji(List<List<String[]>> listall) {

        List<List<String[]>> result = new ArrayList<List<String[]>>();
        if (listall.size() > 1) {

            //先做第一次笛卡尔积
            List<String[]> first = listall.get(0);
            List<String[]> second = listall.get(1);
            for (String[] t1 : first) {
                for (String[] t2 : second) {
                    List<String[]> temp = new ArrayList<String[]>();
                    temp.add(t1);
                    temp.add(t2);
                    result.add(temp);
                }
            }
            if (listall.size() > 2) {
                for (int i = 2; i < listall.size(); i++) {
                    List<List<String[]>> current = new ArrayList<List<String[]>>();
                    for (List<String[]> t : result) {
                        List<String[]> t_second = listall.get(i);
                        for (String[] t_second_per : t_second) {
                            List<String[]> tmp = new ArrayList<String[]>(t);
                            tmp.add(t_second_per);
                            current.add(tmp);
                        }
                    }
                    result = current;
                }

            }
        } else if (listall.size() == 1) {
            //result = listall;
            List<String[]> first = listall.get(0);
            for (String[] t : first) {
                List<String[]> tmp = new ArrayList<String[]>();
                tmp.add(t);
                result.add(tmp);
            }
        }

        return result;
    }

}