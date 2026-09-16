# -*- coding: utf-8 -*-
"""
Created on Wed Feb 10 10:26:23 2021

@author: zbgz4n
"""

# -*- coding: utf-8 -*-
"""
Created on Thu Jan 28 09:52:44 2021

@author: zbgz4n
"""

import pandas as pd
import requests as rq
from requests.exceptions import HTTPError

# -----------------------------------------------------------------------------------------------------------------------------------------
# Define The Dictionaries
# -----------------------------------------------------------------------------------------------------------------------------------------
urls = {
        'login' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/session/login',
        'logout' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/session/logout',
        'program-classes' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/key/program-classes',
        'program-groups' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/key/program-groups',
        'programs' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/key/programs',
        'program-lookup' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/key/program-lookup',
        'parameters' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/key/parameters',
        'data-types' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/key/data-types',
        'products' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/key/products',
        'technologies' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/key/technologies',
        'equipment' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/key/equipment',
        'families' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/key/families',
        'processsteps' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/key/processsteps',
        'stages' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/key/stages',
        'processes' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/key/processes',
        'fabs' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/key/fabs',
        'recipes' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/key/recipes',
        'operators' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/key/operators',
        'memories' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/key/memories',
        'bitmap-tests' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/key/bitmap-tests',
        'pattern-sets' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/key/pattern-sets',
        'equipment1' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/key/equipment1',
        'equipment2' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/key/equipment2',
        'equipment3' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/key/equipment3',
        'equipment4' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/key/equipment4',
        'equipment5' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/key/equipment5',
        'equipment6' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/key/equipment6',
        'lot-classes' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/key/lot-classes',
        'wafer-classes' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/key/wafer-classes',
        'wafers' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/key/wafers',
        'lots' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/key/lots',
        'statistics' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/key/statistics',
        'program-limits' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/key/program-limits',
        'lot-wafer-lookup' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/key/lot-wafer-lookup',
        'lot-events' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/key/lot-events',
        'date-events' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/key/date-events',
        'historical-bins' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/key/historical-bins',
        'program-def' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/key/program-def',
        'bin-colors' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/key/bin-colors',
        'raw-sql' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/key/raw-sql',
        'advanced-dates' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/key/advanced-dates',
        'wmap-config' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/key/wmap-config',
        'lot-gen' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/key/lot-gen',
        'parameter-conditions' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/key/parameter-conditions',
        'wafer-statistics' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/result/wafer-statistics',
        'lot-statistics' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/result/lot-statistics',
        'results' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/result/results',
        'limits' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/result/limits',
        'bins' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/result/bins',
        'event-data' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/result/event-data',
        'leh-weh-data' : 'https://spark-mas-01.canyon.aws.pdf.com/api/v1/result/leh-weh-data',
}

# Update with your Login Credentials Here
ex_cred = {'PROD': '{"username" : "USER", "password" : "Pass", "dbname" : "PROD", "dbschema" : "PRODUCTION"}'}
          

resultsIndexNames = {'wf_key' : 'wf_key',
                     'die_index' : 'partid',
                     'ecid' : 'None',
                     'pass_or_fail' : 'BinState',
                     'test_site' : 'site',
                     'touchdown_num' : 'None',
                     'die_x' : 'die_x',
                     'die_y' : 'die_y',
                     'orig_x' : 'None',
                     'orig_y' : 'None',
                     'reticle_x' : 'None',
                     'reticle_y' : 'None',
                     'reticle_id' : 'None',
                     'hard_bin' : 'hbin',
                     'soft_bin' : 'sbin',
                     'bindesc' : 'BinName'}


# -----------------------------------------------------------------------------------------------------------------------------------------
# Define API Query Functions
# -----------------------------------------------------------------------------------------------------------------------------------------
def exAPI_Login():
    '''Query the Hosted Exensio API Login Method
    with SIC_API_USER credentials and request a Bearer Token.

    Returns
    -------
    token : str
        Hosted Exensio API Bearer Token that can be used to authenticate https connections.
        Token returned is None if there is an Error.

    Raises
    ------
    Exception : queryParamsError
        If there is an error Generating Query Params for API Login.

    HTTPError : loginHTTPError
        If there is an HTTP error when querying the API Login Method.

    Exception : loginError
        If there is an error when querying the API Login Method.

    Exception : loginResponseError
        If there is an error with the format of the API Login Query Response JSON Object.

    '''

    # Generate the Hosted Exensio API Query Parameters
    try:
        url = urls['login']
        cred = ex_cred['PROD']
        headers = {"Connection": "Close"}
        
    except Exception as queryParamsError:
        print('''Error Generating Query Params for API Login Method.
                Error Message {}.'''.format(queryParamsError))
    
    # Query the Hosted Exensio API Login Method
    try:
        response = rq.request('POST', url, headers = headers, data = cred)
        
    except HTTPError as loginHTTPError:
        print('''HTTP Error when querying the API Login Method.
                Error Message {}.'''.format(loginHTTPError))
                
        return None
        
    except Exception as loginError:
        print('''Error when querying the API Login Method.
                Error Message {}.'''.format(loginError))
                
        return None
    
    # Extract the Bearer Token
    if response.ok:
        try:
            token = response.json()['token']
            return token
        
        except Exception as loginResponseError:
            print('''Error with the API Login Response JSON Object format.
                    Error Message {}.'''.format(loginResponseError))
                    
            return None
    
    else:
        print('''API Login Query Response not Valid.
                Response Status Code {}.'''.format(response.status_code))
                
        return None


def exAPI_Logout(token):
    '''Query the Hosted Exensio API Logout Method
    to logout of the current session.

    Parameters
    ----------
    token : str
        The Hosted Exensio Bearer Token that will be used for
        Authenticating the https connection.

    Returns
    -------
    token : str
        Hosted Exensio API Bearer Token that can be used to authenticate https connections.
        Token returned is None if there is an Error.

    Raises
    ------
    Exception : queryParamsError
        If there is an error Generating Query Params for API Login.

    HTTPError : logoutHTTPError
        If there is an HTTP error when querying the API Logout Method.

    Exception : logoutError
        If there is an error when querying the API Logout Method.

    Exception : logoutResponseError
        If there is an error with the format of the API Login Query Response JSON Object.

    '''

    # Generate the Hosted Exensio API Query Parameters
    try:
        url = urls['logout']
        cred = ex_cred['PROD']
        
        # Create Header Info
        headers = {"Authorization": "Bareer "+token,
                   "Content-Type": "application/json",
                   "Connection": "Close"}
        
    except Exception as queryParamsError:
        print('''Error Generating Query Params for API Logout Method.
                Error Message {}.'''.format(queryParamsError))
    
    # Query the Hosted Exensio API Login Method
    try:
        response = rq.request('POST', url, headers = headers, data = cred)
        if response.ok:
            return True
        
        else:
            print('''API Programs Query Response not Valid.
                    Response Status Code {}.'''.format(response.status_code))
                    
            return False
        
    except HTTPError as logoutHTTPError:
        print('''HTTP Error when querying the API Logout Method.
                Error Message {}.'''.format(logoutHTTPError))
                
        return False
        
    except Exception as logoutError:
        print('''Error when querying the API Login Method.
                Error Message {}.'''.format(logoutError))
    
        return False


def exAPI_Programs(token, pgc_key = 1, ppid = None):
    '''Query the Hosted Exensio API Programs Method

    Parameters
    ----------
    token : str
        The Hosted Exensio Bearer Token that will be used for
        Authenticating the https connection.

    pgc_key : int
        The program class key.
        
    ppid : str
        The parametric program id.

    Returns
    -------
    response : str
        Raw Hosted Exensio API Query Response string for the Programs Method.
        Resposne returned is None if there is an Error.

    Raises
    ------
    Exception : queryParamsError
        If there is an error Generating Query Params for API Programs Method.

    HTTPError : programsHTTPError
        If there is an HTTP error when querying the API Programs Method.

    Exception : programsError
        If there is an error when querying the API Programs Method.

    '''

    # Generate the Hosted Exensio API Query Parameters
    try:
        # Lookup URL
        url = urls['programs']
        
        # Create Header Info
        headers = {"Authorization": "Bareer "+token,
                   "Content-Type": "application/json",
                   "Connection": "Close"}
        
        # Create Body Text
        start = '{ "pgc_keys" : ['+ str(pgc_key)+ ']'
        
        if ppid != None:
            ppids = ', "ppids" : ["' + ppid + '"]'
            
        else:
            ppids = ''
                    
        end = ' }'
        
        bodyText = start + ppids + end

    except Exception as queryParamsError:
        print('''Error Generating Query Params for API Programs Method.
                Error Message {}.'''.format(queryParamsError))

    # Query the Hosted Exensio API Programs Method
    try:
        response = rq.request("POST", url, headers = headers, data = bodyText)
        if response.ok:
            return response
        
        else:
            print('''API Programs Query Response not Valid.
                    Response Status Code {}.'''.format(response.status_code))
                    
            return None

    except HTTPError as programsHTTPError:
        print('''HTTP Error when querying the API Programs Method.
                Error Message {}.'''.format(programsHTTPError))
                
        return None
        
    except Exception as programsError:
        print('''Error when querying the API Programs Method.
                 Error Message {}.'''.format(programsError))
                 
        return None
    
    
def exAPI_ProgramLookup(token, lot_keys = None, wafer_keys = None):
    '''Query the Hosted Exensio API Program-Lookup Method

    Parameters
    ----------
    token : str
        The Hosted Exensio Bearer Token that will be used for
        Authenticating the https connection.

    lot_keys : list
        List of integer Lot Keys to filter by.
        
    wafer_keys : list
        List of integer Wafer Keys to filter by.

    Returns
    -------
    response : str
        Raw Hosted Exensio API Query Response string for the Program-Lookup Method.
        Resposne returned is None if there is an Error.

    Raises
    ------
    Exception : queryParamsError
        If there is an error Generating Query Params for API Program-Lookup Method.

    HTTPError : programLookupHTTPError
        If there is an HTTP error when querying the API Program-Lookup Method.

    Exception : programLookupError
        If there is an error when querying the API Program-Lookup Method.

    '''

    # Generate the Hosted Exensio API Query Parameters
    try:
        # Lookup URL
        url = urls['program-lookup']
    
        # Create Header Info
        headers = {"Authorization": "Bareer "+token, "Connection": "Close"}
        
        # Create Body Text
        bodyText = '{ "lot_keys" : [2776623], "wafer_keys" : [4633046] }'
        # Create Body Text
        start = '{ '
        
        if lot_keys != None:
            lots = ', "lot_keys" : [' + str(lot_keys) + ']'
            
        else:
            lots = ''

        if wafer_keys != None:
            wafers = ', "wafer_keys" : [' + str(wafer_keys) + ']'
            
        else:
            wafers = ''
                    
        end = ' }'
        
        bodyText = start + lots + wafers + end

    except Exception as queryParamsError:
        print('''Error Generating Query Params for API Program-Lookup Method.
                Error Message {}.'''.format(queryParamsError))

    # Query the Hosted Exensio API Program-Lookup Method
    try:
        response = rq.request("POST", url, headers = headers, data = bodyText)
        if response.ok:
            return response
        
        else:
            print('''API Program-Lookup Query Response not Valid.
                    Response Status Code {}.'''.format(response.status_code))
                    
            return None

    except HTTPError as programLookupHTTPError:
        print('''HTTP Error when querying the API Program-Lookup Method.
                Error Message {}.'''.format(programLookupHTTPError))
                
        return None
        
    except Exception as programLookupError:
        print('''Error when querying the API Program-Lookup Method.
                 Error Message {}.'''.format(programLookupError))
                 
        return None


def exAPI_Parameters(token, pgc_key = 1, pg_key = None, parameter_type = 'PARAMETRIC'):
    '''Query the Hosted Exensio API Parameters Method

    Parameters
    ----------
    token : str
        The Hosted Exensio Bearer Token that will be used for Authenticating the https connection.

    pgc_key : int
        The program class key to filter by.
        
    pg_key : int
        The program key to filter by.
        
    parameter_type : str
        The type of Test parameters to Query.

    Returns
    -------
    response : str
        Raw Hosted Exensio API Query Response string for the Parameters Method.
        Resposne returned is None if there is an Error.

    Raises
    ------
    Exception : queryParamsError
        If there is an error Generating Query Params for API Program-Lookup Method.

    HTTPError : programLookupHTTPError
        If there is an HTTP error when querying the API Program-Lookup Method.

    Exception : programLookupError
        If there is an error when querying the API Program-Lookup Method.

    '''

    # Generate the Hosted Exensio API Query Parameters
    try:
        # Lookup URL
        url = urls['parameters']
    
        # Create Header Info
        headers = {"Authorization": "Bareer "+token, "Connection": "Close"}
        
        # Create Body Text
        start = '{ "pgc_key" : '+ str(pgc_key)
        
        if pg_key != None:
            pg_keys = ', "pg_keys" : [' + str(pg_key) + ']'
            
        else:
            pg_keys = ''
                    
        end = ', "parameter_type" : "'+ parameter_type +'" }'
        
        bodyText = start + pg_keys + end

    except Exception as queryParamsError:
        print('''Error Generating Query Params for API Parameters Method.
                Error Message {}.'''.format(queryParamsError))

    # Query the Hosted Exensio API Parameters Method
    try:
        response = rq.request("POST", url, headers = headers, data = bodyText)
        if response.ok:
            return response
        
        else:
            print('''API Parameters Query Response not Valid.
                    Response Status Code {}.'''.format(response.status_code))
                    
            return None

    except HTTPError as parametersHTTPError:
        print('''HTTP Error when querying the API Parameters Method.
                                  Error Message {}.'''.format(parametersHTTPError))
                                  
        return None
        
    except Exception as parametersError:
        print('''Error when querying the API Parameters Method.
                                  Error Message {}.'''.format(parametersError))
                                  
        return None
    

def exAPI_Lots(token, FabLotIDs = None):
    '''Query the Hosted Exensio API Lots Method

    Parameters
    ----------
    token : str
        The Hosted Exensio Bearer Token that will be used for Authenticating the https connection.

    FabLotIDs : list
        List of string Fab LotIDs to filter by.

    Returns
    -------
    response : str
        Raw Hosted Exensio API Query Response string for the Lots Method.
        Resposne returned is None if there is an Error.

    Raises
    ------
    Exception : queryParamsError
        If there is an error Generating Query Params for API Lots Method.

    HTTPError : lotsHTTPError
        If there is an HTTP error when querying the API Lots Method.

    Exception : lotsError
        If there is an error when querying the API Lots Method.

    '''

    # Generate the Hosted Exensio API Query Parameters
    try:
        # Lookup URL
        url = urls['lots']
    
        # Create Header Info
        headers = {"Authorization": "Bareer "+token, "Content-Type": "text/plain", "Connection": "Close"}
    
        # Create Body Text
        if FabLotIDs != None:
            start = '{ "lot_ids": ['
            end = '] }'
            lots = ', '.join('"{0}"'.format(FabLotID) for FabLotID in FabLotIDs)
            bodyText = start + lots + end
            
        else:
            bodyText = ''

    except Exception as queryParamsError:
        print('''Error Generating Query Params for API Lots Method.
                Error Message {}.'''.format(queryParamsError))

    # Query the Hosted Exensio API Lots Method
    try:
        response = rq.request("POST", url, headers = headers, data = bodyText)
        if response.ok:
            return response
        
        else:
            print('''API Lots Query Response not Valid.
                    Response Status Code {}.'''.format(response.status_code))
                    
            return None

    except HTTPError as lotsHTTPError:
        print('''HTTP Error when querying the API Lots Method.
                                  Error Message {}.'''.format(lotsHTTPError))
                                  
        return None
        
    except Exception as lotsError:
        print('''Error when querying the API Lots Method.
                                  Error Message {}.'''.format(lotsError))
                                  
        return None


def exAPI_LotWaferLookup(token, FabLotIDs = None, FabWaferIDs = None):
    '''Query the Hosted Exensio API Lot-Wafer-Lookup Method

    Parameters
    ----------
    token : str
        The Hosted Exensio Bearer Token that will be used for Authenticating the https connection.

    FabLotIDs : list
        List of string Fab LotIDs to filter by.

    FabWaferIDs : list
        List of string Fab WaferIDs to filter by.

    Returns
    -------
    response : str
        Raw Hosted Exensio API Query Response string for the Lot-Wafer-Lookup Method.
        Resposne returned is None if there is an Error.

    Raises
    ------
    Exception : queryParamsError
        If there is an error Generating Query Params for API Lot-Wafer-Lookup Method.

    HTTPError : lotWaferLookupHTTPError
        If there is an HTTP error when querying the API Lot-Wafer-Lookup Method.

    Exception : lotWaferLookupError
        If there is an error when querying the API Lot-Wafer-Lookup Method.

    '''

    # Generate the Hosted Exensio API Query Parameters
    try:
        # Lookup URL
        url = urls['lot-wafer-lookup']
    
        # Create Header Info
        headers = {"Authorization": "Bareer "+token, "Content-Type": "application/json", "Connection": "Close"}
        
        # Create Body Text
        start = '{ "pgc_key" : 1, '
        
        if FabLotIDs != None and FabLotIDs != []:
            FabLotList = ', '.join('"{0}"'.format(FabLotID) for FabLotID in FabLotIDs)
            FabLotList = ' "lot_ids" : [' + FabLotList + ']'
            
        else:
            FabLotList = ''
            
        #
        if FabWaferIDs != None and FabWaferIDs != []:
            FabWaferList = ', '.join('"{0}"'.format(FabWafer) for FabWafer in FabWaferIDs)
            FabWaferList = ' "wafer_ids" : [' + FabWaferList + ']'
            
        else:
            FabWaferList = ''
            
        end = ' }'
        
        bodyText = start + FabLotList + FabWaferList + end

    except Exception as queryParamsError:
        print('''Error Generating Query Params for API Lot-Wafer-Lookup Method.
                Error Message {}.'''.format(queryParamsError))

    # Query the Hosted Exensio API Lots Method
    try:
        response = rq.request("POST", url, headers = headers, data = bodyText)
        if response.ok:
            return response
        
        else:
            print('''API Lot-Wafer-Lookup Query Response not Valid.
                    Response Status Code {}.'''.format(response.status_code))
                    
            return None
        
    except HTTPError as lotWaferLookupHTTPError:
        print('''HTTP Error when querying the API Lot-Wafer-Lookup Method.
                                  Error Message {}.'''.format(lotWaferLookupHTTPError))
                                  
        return None
        
    except Exception as lotWaferLookupError:
        print('''Error when querying the API Lot-Wafer-Lookup Method.
                                  Error Message {}.'''.format(lotWaferLookupError))
                                  
        return None


def exAPI_Results(token, pgc_key = 1, rework_criteria = 'LATEST', test_indexes = None, stat_keys = ''):
    '''Query the Hosted Exensio API Results Method

    Parameters
    ----------
    token : str
        The Hosted Exensio Bearer Token that will be used for Authenticating the https connection.

    pgc_key : int
        The program class key to filter by.

    rework_criteria : str
        Rework Criteria to filter by.
        
    test_indexes : str
        List of Test Numbers to filter by.
        
    stat_keys : str
        Program Key, Wafer Key pairs to filter by.

    Returns
    -------
    response : str
        Raw Hosted Exensio API Query Response string for the Results Method.
        Resposne returned is None if there is an Error.

    Raises
    ------
    Exception : queryParamsError
        If there is an error Generating Query Params for API Results Method.

    HTTPError : resultsHTTPError
        If there is an HTTP error when querying the API Results Method.

    Exception : resultsLookupError
        If there is an error when querying the API Results Method.

    '''

    # Generate the Hosted Exensio API Query Parameters
    try:
        # Lookup URL
        url = urls['results']
    
        # Create Header Info
        headers = {"Authorization": "Bareer "+token, "Connection": "Close"}
            
        # Create Body Text
        start = '{ "pgc_key" : '+ str(pgc_key)
        
        rework = ', "rework_criteria" : "'+ rework_criteria +'"'
        
        if test_indexes != None and test_indexes != []:
            tests = ', "test_indexes" : ' +str(test_indexes)
            
        else:
            tests = ''
            
        if stat_keys != '':
            stats = ', ' + stat_keys
            
        else:
            stats = ''
            
        end = ' }'
        
        bodyText = start + rework + tests + stats + end

    except Exception as queryParamsError:
        print('''Error Generating Query Params for API Results Method.
                Error Message {}.'''.format(queryParamsError))

    # Query the Hosted Exensio API Results Method
    try:
        response = rq.request("POST", url, headers = headers, data = bodyText)
        if response.ok:
            return response
        
        else:
            print('''API Results Query Response not Valid.
                    Response Status Code {}.'''.format(response.status_code))
                    
            return None

    except HTTPError as resultsHTTPError:
        print('''HTTP Error when querying the API Results Method.
                                  Error Message {}.'''.format(resultsHTTPError))
                                  
        return None
        
    except Exception as resultsError:
        print('''Error when querying the API Results Method.
                                  Error Message {}.'''.format(resultsError))
                                  
        return None
    

# -----------------------------------------------------------------------------------------------------------------------------------------
# Define API Query Response Parse Functions
# -----------------------------------------------------------------------------------------------------------------------------------------
def parse_Programs(FabWaferID, response):
    '''Parse the Hosted Exensio API Programs Method Response
    to determine the number of Index Tests for the
    Fab Wafer of Interest

    Parameters
    ----------
    FabWaferID : str
        The Fab Wafer of Interest.

    response : str
        Hosted Exensio Raw Response string.

    Returns
    -------
    numIdexes : int
        The number of Index Tests parsed form the Programs Method Response.
        numIdexes returned is None if there is an Error.

    Raises
    ------
    Exception : parseProgramsError
        If there is an error parsing the Response from the Programs Method

    '''

    try:
        # Get the Response Text as a JSON Object
        jsonResponse = response.json()
        
        # Get the number of Test Programs returned in the Response
        numPrograms = len(jsonResponse.keys())
        
        # Return None if more than one Test Program
        if numPrograms > 1:
            print('More than one Test Program found for FabWafer {}.'.format(FabWaferID))
            
            return None
        
        # Parse the number of Index Tests from the Programs Response
        numIdexes = jsonResponse['programs'][0]['indexes']
        
        return numIdexes
            
    except Exception as parseProgramsError:
        print('''Error parsing Hosted Exensio API Programs Method Response.
                                  Error Message {}'''.format(parseProgramsError))
       
        return None


def parse_Parameters(FabWaferID, response):
    '''Parse the Hosted Exensio API Parameters Method Response
    to determine the Test Indexes of Interest

    Parameters
    ----------
    FabWaferID : str
        The Fab Wafer of Interest.

    response : str
        Hosted Exensio Raw Response string.

    Returns
    -------
    test_indexes : list
        A list of Test Indexes of Interest parsed form the Parameters Method Response.
        test_indexes returned is None if there is an Error.

    Raises
    ------
    Exception : parseParametersError
        If there is an error parsing the Response from the Parameters Method

    '''

    try:
        # Get the Response Text as a JSON Object
        jsonResponse = response.json()
        
        # Get the number of Test Programs returned in the Response
        numParams = len(jsonResponse.keys())
        
        # Return Empty List if more than one Test Program
        if numParams > 1:
            print('More than one Parameter Set returned for FabWaferID {}'.format(FabWaferID))
            return []
        
        # Create a list of Test Indexes, excluding tests with no useful data
        excludeTests = ['SAME', 'TIME', 'DELAY']
        test_indexes = [int(testDict['test_index']) for testDict in jsonResponse['parameters'] 
                        if testDict['unit'] != None and not any(test in testDict['name'] for test in excludeTests)]
        
        # Insert the Index Test numbers into the list
        for test in range(1,test_indexes[0]):
            test_indexes.insert(test-1,test)

        return test_indexes
        
    except Exception as parseParametersError:
        print('Error occurred: {}'.format(parseParametersError))
        
        return []


def parse_LotWaferLookup(FabFamilyID, FabWaferID, response):
    '''Parse the Hosted Exensio API Lot-Wafer-Lookup Method Response
    to determine the FabLotKey, FabWaferKey, pg_key and ppid
    foer the given Fab Wafer of Interest.

    Parameters
    ----------
    FabFamilyID : str
        The Fab Family to use to search for the ppid.

    FabWaferID : str
        The Fab Wafer of Interest.

    response : str
        Hosted Exensio Raw Response string.

    Returns
    -------
    FabLotKey  : int
        The Fab Lot Key for the Fab Wafer of Interest.

    FabWaferKey  : int
        The Wafer Key for the Fab Wafer of Interest.

    pg_key  : int
        The Program Key for the Paramteric Test Program.

    ppid  : str
        The Parametric Program ID Name.

    Raises
    ------
    Exception : parseLotWaferError
        If there is an error parsing the Response from the Lots-Wafer-Lookup Method

    '''

    try:
        # Get the Response Text as a JSON Object
        jsonResponse = response.json()

        # Get the number of Fab Lots returned
        numLots = len(jsonResponse.keys())

        # Return None if more than one Fab Lot
        if numLots > 1:
            print('More than one FabLotID found for FabWafer {}.'.format(FabWaferID))
            
            return None, None, None, None
        
        # Parse the FabLotKey, FabWaferKey, pg_key and ppid from the Lot-Wafer-Lookup Response
        FabLotKey = jsonResponse['lots'][0]['lot_key']
        FabWaferKey = None
        pg_key = None
        ppid = None
        for waferDict in jsonResponse['lots'][0]['wafers']:
            if waferDict['wafer_id'] == FabWaferID and ('WS::'+FabFamilyID in waferDict['ppid'] or 'WS::Z'+FabFamilyID in waferDict['ppid']):  # and 'UILSP20' in waferDict['ppid']:
                FabWaferKey = waferDict['wafer_key']
                pg_key = waferDict['pg_key']
                ppid = waferDict['ppid']
                break
            
        return FabLotKey, FabWaferKey, pg_key, ppid 
            
    except Exception as parseLotWaferError:
        print('Error parsing LotWaferLookup Response.\n    Error Message {}'.format(parseLotWaferError))
        return None, None, None, None
        


def parse_Results(FabWaferID, response):
    '''Parse the Hosted Exensio API Results Response
    to determine the FabLotKey, FabWaferKey, pg_key and ppid
    foer the given Fab Wafer of Interest.

    Parameters
    ----------
    FabWaferID : str
        The Fab Wafer of Interest.

    response : str
        Hosted Exensio Raw Response string.

    Returns
    -------
    waferData  : pd.DataFrame()
        The Parametric Test data as a Pandas DataFrame parsed from the
        Hosted Exensio API Results Response for the given Fab Wafer.
        An empty DataFrame is returned if there is an error.

    Raises
    ------
    Exception : parseResultsError
        If there is an error parsing the Response from the Results Method

    '''

    try:
        # Get the Response Text as a JSON Object
        jsonResponse = response.json()

        # Parse the column headers
        headers = [headerDict['test_name'] for headerDict in jsonResponse['results']['result_sets'][0]['testdata']]

        # Update header names based on resultsIndexNames Dictionary
        headers = [resultsIndexNames.get(header, header) for header in headers]

        # Parse the data
        data = jsonResponse['results']['result_sets'][0]['rows']

        # Save the Data in a DataFrame
        waferData = pd.DataFrame(data, columns = headers)
        waferData.set_index('partid', inplace = True)

        # Delete any columns named "None"
        try:
            waferData.drop(['None'], axis=1, inplace = True)
            
        except:
            pass
        
        return waferData
        
    except Exception as parseResultsError:
        print('''Error Parsing Rersults Method API Response.
                Error Message: {}'''.format(parseResultsError))
                
        return pd.DataFrame()


# -----------------------------------------------------------------------------------------------------------------------------------------
# Define Helper Functions
# -----------------------------------------------------------------------------------------------------------------------------------------
def genStatKeys(pg_key, wafer_keys):
    '''Parse the Hosted Exensio API Results Response
    to determine the FabLotKey, FabWaferKey, pg_key and ppid
    foer the given Fab Wafer of Interest.

    Parameters
    ----------
    pg_key : int
        The test progam key.

    wafer_keys : int
        List of integer wafer keys.

    Returns
    -------
    stat_keys  : str
        A formatted string of program key, wafer key pairs that can be used in the
        bodyText of the the Results Method. An empty string is returned if there
        is an error.

    Raises
    ------
    Exception : genStatKeysError
        If there is an error generating the stat key string

    '''

    try:
        # Generate the formatted string of program key, wafer key pairs
        stat_keys = '"stat_keys" : ['
        
        for waferKey in wafer_keys:
            stat_keys = stat_keys + ' { "pg_key" : '+ str(pg_key) + ', "wafer_key" : '+ str(waferKey) + '},'
            
        stat_keys = stat_keys[:-1]
        stat_keys = stat_keys + ' ]'
        
        return stat_keys

    except Exception as genStatKeysError:
        print('''Error Parsing Rersults Method API Response.
                Error Message: {}'''.format(genStatKeysError))

        return ''


# -----------------------------------------------------------------------------------------------------------------------------------------
# Define Program Callable Functions
# -----------------------------------------------------------------------------------------------------------------------------------------
def getWaferData_ExAPI(FabFamilyID, FabWaferID, IndexesOnly = True):
    '''Queries the Hosted Exensio API and returns the Paramteric Test Data for the given Fab Wafer.

    Parameters
    ----------
    FabFamilyID : str
        The FabFamilyID of the Fab Wafer of Interest.

    FabWaferID : str
        The Fab Wafer of Interest.

    IndexesOnly : bool
        Boolean to decide wheter or not to include all parametric data.
        If True, only Index Tests are returned.
        If False, all Index Tests and Paramteric Tests of Interest are returned.

    Returns
    -------
    waferData  : pd.DataFrame()
        The Parametric Test data as a Pandas DataFrame parsed from the
        Hosted Exensio API Results Response for the given Fab Wafer.
        An empty DataFrame is returned if there is an error.

    Raises
    ------
    Exception : lotWaferLookupError
        If there is an error getting a valid resposne from the Lot-Wafer-Lookup Method

    Exception : programsError
        If there is an error getting a valid resposne from the Programs Method

    Exception : parametersError
        If there is an error getting a valid resposne from the Parameters Method

    Exception : resultsError
        If there is an error getting a valid resposne from the Results Method

    '''

    # Store FabWaferID into a list    
    FabWaferIDs = [FabWaferID]
    
    # Get Session Token
    token = exAPI_Login()
    
    # Make Boolen for Return value
    validResponse = True
    
    if token == None:
        print('\n\nError Retrieving Token from Hosted Exensio API for FabWaferID {}.\n'.format(FabWaferID))
        validResponse = False
    
    if validResponse:
        # Query the API to get the FabLotKey, FabWaferKey and Parametric Test Program
        try:
            rp_LotWaferLookup = exAPI_LotWaferLookup(token, FabLotIDs = None, FabWaferIDs = FabWaferIDs)
            if rp_LotWaferLookup != None:
                FabLotKey, FabWaferKey, pg_key, ppid = parse_LotWaferLookup(FabFamilyID, FabWaferID, rp_LotWaferLookup)
                if FabLotKey == None or FabWaferKey == None or pg_key == None or ppid == None:
                    print('''\n\nParametric Test Program not found for FabWafer {}
                    FabLotKey = {}
                    FabWaferKey = {}
                    pg_key = {}
                    ppid = {}'''.format(FabWaferID, FabLotKey, FabWaferKey, pg_key, ppid))
                    validResponse = False
                
            else:
                print('\n\nInvlaid Resposne Received for Lot-Wafer-Lookup Method, FabWaferID {}'.format(FabWaferID))
                validResponse = False
                    
        except Exception as lotWaferLookupError:
            print('\n\nError Querying the API Lots-Wafer-Lookup Method.\n    Error Message: {}'.format(lotWaferLookupError))
            validResponse = False
    
    
    if validResponse:
        if IndexesOnly == True:
            # Query the API programs method to retrieve the number of test indexes
            try:
                rp_Programs = exAPI_Programs(token, pgc_key = 1, ppid = ppid)
                if rp_Programs != None:
                    numIdexes = parse_Programs(FabWaferID, rp_Programs)
                    if numIdexes == 0 or numIdexes == None:
                        print('''\n\nTest Indexes not found for FabWafer {}
                        FabLotKey = {}
                        FabWaferKey = {}
                        pg_key = {}
                        ppid = {}'''.format(FabWaferID, FabLotKey, FabWaferKey, pg_key, ppid))
                        validResponse = False
                    
                    # Create the list of Test_Indexes
                    test_indexes = list(range(1,numIdexes+1))
                    
                else:
                    print('\n\nInvlaid Resposne Received for Programs Method, FabWaferID {}'.format(FabWaferID))
                    validResponse = False
                    
            except Exception as programsError:
                print('\n\nError Querying the API Programs Method.\n    Error Message: {}'.format(programsError))
                validResponse = False
    
        else:
            # Query the API paramters method to get the number of test indexes and the Test Names of Interest
            try:
                rp_Parameters = exAPI_Parameters(token, pgc_key = 1, pg_key = pg_key, parameter_type = 'PARAMETRIC')
                if rp_Parameters != None:
                    test_indexes = parse_Parameters(FabWaferID, rp_Parameters)
                    if test_indexes == 0 or test_indexes == None:
                        print('''\n\nTest Indexes not found for FabWafer {}
                        FabLotKey = {}
                        FabWaferKey = {}
                        pg_key = {}
                        ppid = {}'''.format(FabWaferID, FabLotKey, FabWaferKey, pg_key, ppid))
                        validResponse = False
    
                else:
                    print('\n\nInvlaid Resposne Received for Programs Method, FabWaferID {}'.format(FabWaferID))
                    validResponse = False
                
            except Exception as parametersError:
                print('\n\nError Querying the API Parameters Method.\n    Error Message: {}'.format(parametersError))
                validResponse = False

    if validResponse:
        # Query the API Results Method to get the Paramteric Data
        try:
            FabWaferKeys = [FabWaferKey]
            stat_keys = genStatKeys(pg_key, FabWaferKeys)
            rp_results = exAPI_Results(token, pgc_key = 1, rework_criteria = 'LATEST', test_indexes = test_indexes, stat_keys = stat_keys)
            if rp_results != None:
                waferData = parse_Results(FabWaferID, rp_results)
                if not waferData.empty:
                    waferData.sort_values(by = ['partid'], inplace = True)
                    waferData['Product'] = ppid[ppid.find('::')+2:ppid.find('_')] + '_WDX'
                    waferData['Program'] = ppid
                    waferData['Source Lot'] = FabWaferID[:FabWaferID.find('_')] + '.S'
                    waferData['Lot'] = FabWaferID[:FabWaferID.find('_')]
                    waferData['Wafer'] = FabWaferID
                    waferData['Wafer Number'] = int(FabWaferID[FabWaferID.find('_')+1:])
                
                else:
                    print('''\n\nError Parsing Parametric Data for FabWafer {}.
                    FabLotKey = {}
                    FabWaferKey = {}
                    pg_key = {}
                    ppid = {}'''.format(FabWaferID, FabLotKey, FabWaferKey, pg_key, ppid))
                    validResponse = False
                
            else:
                print('''\n\nError Querying the API Results Method for FabWafer {}.
                FabLotKey = {}
                FabWaferKey = {}
                pg_key = {}
                ppid = {}'''.format(FabWaferID, FabLotKey, FabWaferKey, pg_key, ppid))
                validResponse = False
            
        except Exception as resultsError:
            print('''\n\nError Querying the API Results Method.
            Error Message: {}'''.format(resultsError))
            validResponse = False
    
    exAPI_Logout(token)
    
    if validResponse:
        print('''\n\nParametric Data successfully queried from the Hosted Exensio API for FabWafer {}.
        FabLotKey = {}
        FabWaferKey = {}
        pg_key = {}
        ppid = {}'''.format(FabWaferID, FabLotKey, FabWaferKey, pg_key, ppid))
        return waferData

    else:
        return pd.DataFrame()
    
    
    
# -----------------------------------------------------------------------------------------------------------------------------------------
# Examples
# -----------------------------------------------------------------------------------------------------------------------------------------
FabFamilyID = 'CM8012X'
FabWaferID = 'KG01HK4X_06'
waferData = getWaferData_ExAPI(FabFamilyID, FabWaferID, IndexesOnly = True)
