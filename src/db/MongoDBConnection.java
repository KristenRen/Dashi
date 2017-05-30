package db;
 
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
 
import model.Restaurant;
 
import org.bson.Document;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
 
import yelp.YelpAPI;
 
import com.mongodb.Block;
import com.mongodb.MongoClient;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.UpdateOptions;
 
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.regex;
 
public class MongoDBConnection implements DBConnection {
 
    private static final int MAX_RECOMMENDED_RESTAURANTS = 10;
 
    private MongoClient mongoClient;
    private MongoDatabase db;
 
    public MongoDBConnection() {
   	 // Connects to local mongodb server.
   	 mongoClient = new MongoClient();
   	 db = mongoClient.getDatabase(DBUtil.DB_NAME);
 
    }

	@Override
	public void close() {
		 if (mongoClient != null) {
	   		 mongoClient.close();
	   	 }
	}

	
	//setVisited 相当于MongoDB语句在user id= 1111 文件下，新建document visited，
	//并存入 businessIds;
	/*db.users.update(
		       {
		           user_id:"1111"
		       }, 
		       {
		         $pushAll:
		          {
		             Visited: ["aaaa", "bbbb"]
		          }
		        }
		    )
*/
	@Override
	public void setVisitedRestaurants(String userId, List<String> businessIds) {
		 db.getCollection("users").updateOne(new Document("user_id", userId),
	   			 new Document("$pushAll", new Document("visited", businessIds)));
// pushAll 将整个array放进去，如果是单个可以只push；
	}

	@Override
	public void unsetVisitedRestaurants(String userId, List<String> businessIds) {
		db.getCollection("users").updateOne(new Document("user_id", userId),
	   			 new Document("$pullAll", new Document("visited", businessIds)));
	}

	@Override
	public Set<String> getVisitedRestaurants(String userId) {
		Set<String> set = new HashSet<>();
		// db.users.find({user_id:1111})
		FindIterable<Document> iterable = db.getCollection("users").find(
				eq("user_id", userId));// eq相当于"user_id"==userId
		if (iterable.first().containsKey("visited")) {//用if是因为一个userId只有一个visited;
	   		List<String> list = (List<String>) iterable.first().get("visited");
			set.addAll(list);
	   	}
		return set;

	}

	@Override
	public JSONObject getRestaurantsById(String businessId, boolean isVisited) {
		FindIterable<Document> iterable = db.getCollection("restaurants").find(eq("business_id", businessId));
		try {
			JSONObject obj = new JSONObject(iterable.first().toJson());
			obj.put("is_visited", isVisited);
			return obj;
		} catch (JSONException e) {
			e.printStackTrace();
		}
		return null;

	}

	@Override
	//与Mysql完全一样，可以写一个abstract class来优化；
	public JSONArray recommendRestaurants(String userId) {
		try {
			
			Set<String> visitedRestaurants = getVisitedRestaurants(userId);//step 1
			Set<String> allCategories = new HashSet<>();// why hashSet? //step 2
			for (String restaurant : visitedRestaurants) {
				allCategories.addAll(getCategories(restaurant));
			}
			Set<String> allRestaurants = new HashSet<>();//step 3
			for (String category : allCategories) {
				Set<String> set = getBusinessId(category);
				allRestaurants.addAll(set);
			}
			Set<JSONObject> diff = new HashSet<>();//step 4
			int count = 0;
			for (String businessId : allRestaurants) {
				// Perform filtering
				if (!visitedRestaurants.contains(businessId)) {
					diff.add(getRestaurantsById(businessId, false));
					count++;
					if (count >= MAX_RECOMMENDED_RESTAURANTS) {
						break;
					}
				}
			}
			return new JSONArray(diff);
		} catch (Exception e) {
			System.out.println(e.getMessage());
		}
		return null;

	}

	@Override
	public Set<String> getCategories(String businessId) {
		Set<String> set = new HashSet<>();
		FindIterable<Document> iterable = db.getCollection("restaurants").find(eq("business_id", businessId));

		if (iterable.first().containsKey("categories")) {
			String[] categories = iterable.first().getString("categories").split(",");
			for (String category : categories) {
				set.add(category.trim());
			}
		}
		return set;

	}

	@Override
	public Set<String> getBusinessId(String category) {
		 Set<String> set = new HashSet<>();
         
	 FindIterable<Document> iterable = db.getCollection("restaurants").find(
			 regex("categories", category));
	// regex similar to LIKE %category% in MySQL
     // Chinese, Japanese, Korean -> Japanese
	 iterable.forEach(new Block<Document>() {
		 //forEach() 对iterable中每一个进行某个操作；
		 @Override
		 public void apply(final Document document) {
			 set.add(document.getString("business_id"));
		 }
	 });
	 return set;

	}

	@Override
	public JSONArray searchRestaurants(String userId, double lat, double lon, String term) {
		try {
			YelpAPI api = new YelpAPI();
			JSONObject response = new JSONObject(
					api.searchForBusinessesByLocation(lat, lon));
			JSONArray array = (JSONArray) response.get("businesses");
 
			List<JSONObject> list = new ArrayList<JSONObject>();
			Set<String> visited = getVisitedRestaurants(userId);
 
			for (int i = 0; i < array.length(); i++) {
				JSONObject object = array.getJSONObject(i);
				Restaurant restaurant = new Restaurant(object);
				String businessId = restaurant.getBusinessId();
				String name = restaurant.getName();
				String categories = restaurant.getCategories();
				String city = restaurant.getCity();
				String state = restaurant.getState();
				String fullAddress = restaurant.getFullAddress();
				double stars = restaurant.getStars();
				double latitude = restaurant.getLatitude();
				double longitude = restaurant.getLongitude();
				String imageUrl = restaurant.getImageUrl();
				String url = restaurant.getUrl();
				JSONObject obj = restaurant.toJSONObject();
				if (visited.contains(businessId)) {
					obj.put("is_visited", true);
				} else {
					obj.put("is_visited", false);
				}
				// Question: why using upsert instead of insert directly?
				//第一次做insert，之后做update
				UpdateOptions options = new UpdateOptions().upsert(true);
 
				db.getCollection("restaurants").updateOne(
						new Document().append("business_id", businessId),
						new Document("$set",
							new Document()
									.append("business_id", businessId)
									.append("name", name)
									.append("categories", categories)
									.append("city", city).append("state", state)
									.append("full_address", fullAddress)
									.append("stars", stars)
									.append("latitude", latitude)
									.append("longitude", longitude)
									.append("image_url", imageUrl)
									.append("url", url)),
						options);
				list.add(obj);
			}
				return new JSONArray(list);
		} catch (Exception e) {
			System.out.println(e.getMessage());
		}
		return null;
	}


	@Override
	public Boolean verifyLogin(String userId, String password) {
		 FindIterable<Document> iterable = db.getCollection("users").find(
	   			 new Document("user_id", userId));
	   	 Document document = iterable.first();
	   	 return document.getString("password").equals(password);

	}

	@Override
	public String getFirstLastName(String userId) {
		FindIterable<Document> iterable = db.getCollection("users").find(
	   			 new Document("user_id", userId));
	   	 Document document = iterable.first();
	   	 String firstName = document.getString("first_name");
	   	 String lastName = document.getString("last_name");
	   	 return firstName + " " + lastName;

	}
}
