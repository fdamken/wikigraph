<!DOCTYPE html>
<html>
<head>
	<link rel="stylesheet" href="/alchemy/alchemy.min.css">
	<style>
		body {
			margin : 0;
		}
	</style>

	<script src="/d3.min.js"></script>
	<script src="/jquery.min.js"></script>
	<script src="/underscore.min.js"></script>
	<script src="/alchemy/alchemy.min.js"></script>
</head>
<body>
	<div id="alchemy" class="alchemy">
		Loading… This takes a time as Wikipedia is big. Please be patient.
	</div>

	<script>
		// Some bugfixes as the documentation of alchemy is pretty
		// outdated and it does not work as-is.

		_.cloneDeep = function(obj) {
			return $.extend({}, obj);
		};
		_.merge = $.extend;
		_.zip = function() {
		return _.unzip(arguments);
		};
		_.unzip = function(array) {
			var length = array && array.length || 0;
			var result = Array(length);

			for (var index = 0; index < length; index++) {
				result[index] = _.pluck(array, index);
			}
			return result;
		};
		_.zipObject = _.zip;
	</script>

	<script>
		window.alchemy = new Alchemy({
			dataSource : '/api/article',
			forceLocked : true,
			nodeCaption : 'title',
			nodeCaptionsOnByDefault : true,
			directedEdges : true
		});
	</script>
</body>
</html>
