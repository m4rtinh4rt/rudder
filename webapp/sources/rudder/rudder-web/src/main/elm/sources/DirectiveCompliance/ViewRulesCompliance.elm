module DirectiveCompliance.ViewRulesCompliance exposing (..)

import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (onClick, onInput)
import List
import List.Extra
import Tuple3
import Dict

import DirectiveCompliance.ApiCalls exposing (..)
import DirectiveCompliance.DataTypes exposing (..)
import DirectiveCompliance.ViewUtils exposing (..)
import Compliance.Utils exposing (displayComplianceFilters, filterDetailsByCompliance)
import Ui.Datatable exposing (filterSearch, SortOrder(..), generateLoadingTable)


displayRulesComplianceTable : Model -> Html Msg
displayRulesComplianceTable model =
  let
    filters = model.ui.ruleFilters
    complianceFilters = model.ui.complianceFilters
    fun     = byRuleCompliance model (nodeValueCompliance model complianceFilters) complianceFilters
    col     = "Rule"
    childs  = case model.directiveCompliance of
      Just dc -> dc.rules
      Nothing -> []
    childrenSort = childs
      |> List.filter (\d -> (filterSearch filters.filter (searchFieldRuleCompliance d)))
      |> List.filter (filterDetailsByCompliance complianceFilters)
      |> List.sortWith sort

    (children, order, newOrder) = case sortOrder of
       Asc -> (childrenSort, "asc", Desc)
       Desc -> (List.reverse childrenSort, "desc", Asc)

    rowId = "by" ++ col ++ "s/"
    rows = List.map Tuple3.first fun.rows
    (sortId, sortOrder) = Dict.get rowId filters.openedRows |> Maybe.withDefault (col, Asc)
    sort =   case List.Extra.find (Tuple3.first >> (==) sortId) fun.rows of
      Just (_,_,sortFun) -> (\i1 i2 -> sortFun (fun.data model i1) (fun.data model i2))
      Nothing -> (\_ _ -> EQ)
  in
    ( if model.ui.loading then
      generateLoadingTable True 2
      else
      div[][ div [class "table-header extra-filters"]
      [ div[class "main-filters"]
        [ input [type_ "text", placeholder "Filter", class "input-sm form-control", value filters.filter
          , onInput (\s -> (UpdateFilters {filters | filter = s} ))][]
        , button [class "btn btn-default btn-sm btn-icon", onClick (UpdateComplianceFilters {complianceFilters | showComplianceFilters = not complianceFilters.showComplianceFilters}), style "min-width" "170px"]
          [ text ((if complianceFilters.showComplianceFilters then "Hide " else "Show ") ++ "compliance filters")
          , i [class ("fa " ++ (if complianceFilters.showComplianceFilters then "fa-minus" else "fa-plus"))][]
          ]
        , div [class "ms-auto my-auto"]
          [ button [class "btn btn-sm btn-primary btn-export me-2", onClick (CallApi getCSVExport) ]
            [ text "Export " , i [ class "fa fa-download" ] [] ]
          , button [class "btn btn-sm btn-default btn-refresh", onClick (CallApi getDirectiveCompliance)]
            [ i [class "fa fa-refresh"][] ]
          ]
        ]
      , displayComplianceFilters complianceFilters UpdateComplianceFilters
      ]
      , div[class "table-container"]
        [ table [class "dataTable compliance-table"]
          [ thead []
            [ tr [ class "head" ]
              ( List.map (\row -> th [onClick (ToggleRowSort rowId row (if row == sortId then newOrder else Asc)), class ("sorting" ++ (if row == sortId then "_"++order else ""))] [ text row ]) rows )
            ]
          , tbody []
            ( if List.length childs <= 0 then
              [ tr[]
                [ td[class "empty", colspan 2][i [class"fa fa-exclamation-triangle"][], text "There is no compliance for this directive."] ]
              ]
            else if List.length children == 0 then
              [ tr[]
                [ td[class "empty", colspan 2][i [class"fa fa-exclamation-triangle"][], text "No rules match your filter."] ]
              ]
            else
              List.concatMap (\d ->  showComplianceDetails fun d "" filters.openedRows model) children
            )
          ]
        ]
      ])
