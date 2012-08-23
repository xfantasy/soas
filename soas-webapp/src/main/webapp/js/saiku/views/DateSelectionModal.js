//新增一个date_test,继承Modal

var DateSelectionModal= Modal.extend({
    type:"DateSelectionModal",
    events: {
        'click a': 'call'//,
       // 'onSelect': 'call'
    },
    initialize:function(args){
        _.extend(this, args);
        //query对象
        my_query = args.workspace.query;
       //member对象
        my_member = new Member({}, {
            cube: args.workspace.selected_cube,
            dimension: args.key
        });

        //axis对象
        my_axis = "undefined";
        if (args.target.parents('.fields_list_body').hasClass('rows')) {
            my_axis = "ROWS";
        }
        if (args.target.parents('.fields_list_body').hasClass('columns')) {
            my_axis = "COLUMNS";
        }
        if (args.target.parents('.fields_list_body').hasClass('filter')) {
            my_axis = "FILTER";
        }

    } ,

    start_this:function(){
            $(this.target).parent().children('.selections').datepicker({
                changeYear:true,
                changeMonth:true,
                showButtonPanel:true,
                //closeText:true,
                gotoCurrent:true,
                dateFormat:'yy-mm-dd',
                yearRange:'1990:1999',
            onSelect:function(dateText,inst){
                year_select=dateText.split('-')[0];//获得年份信息
                month_select=dateText.split('-')[1];//获得月份信息
                day_select=dateText.split('-')[2];//获得日期
                $('.ui-datepicker').remove();
                $(this).parent().children('.selections ').removeClass("hasDatepicker");
                //添加save功能

                var updates = [{
                    hierarchy: my_member.hierarchy,
                    uniquename: my_member.level,
                    type: 'level',
                    action: 'delete'
                }];
                var value=unescape(my_member.level.replace("Year",year_select));
                updates.push({
                    uniquename: value,
                    type: 'member',
                    action: 'add'
                });
                my_query.action.put('/axis/' + my_axis + '/dimension/' + my_member.dimension, {
                    success:  function() {
                        my_query.run();
                    },
                    data: {
                        selections: JSON.stringify(updates)
                    }
                });

                return false;
                }



        });
        $('.ui-datepicker').css({position:"absolute"});//使得不被隐藏起来



    }

});