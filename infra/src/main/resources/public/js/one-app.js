var oneApp = {
	scope : '#main',
	init : function() {
		var that = this;
		that.i18n.load();
		$('body').delegate(that.scope, 'click',function(event) {
			if (!event.target.getAttribute('call')) return;
			event.preventDefault();
			if(event.target.getAttribute('disabled') !== null){
				return;
			}
			var call = event.target.getAttribute('call');
			that.action[call]({url : event.target.getAttribute('href'), target: event.target});
			event.stopPropagation();
		});
		// TODO : refactor web message dispatch policiy
		window.addEventListener('message', that.message.css, false);
	},
	action : {
	},
	template : {
		getAndRender : function (pathUrl, templateName, elem, dataExtractor){
			var that = this;
			if (_.isUndefined(dataExtractor)) {
				dataExtractor = function (d) { return {list : _.values(d.result)}; };
			}
			$.get(pathUrl)
				.done(function(data) {
					$(elem).html(that.render(templateName, dataExtractor(data)));
				})
				.error(function(data) {
					oneApp.notify.error(data);
				});
		},
		render : function (name, data) {
			_.extend(data, {
				'i18n' : oneApp.i18n.i18n,
				'formatDate' : function() {
					return function(str) {
						var dt = new Date(Mustache.render(str, this).replace('CEST', 'EST')).toShortString();
						return dt;
					};
				},
				'formatDateTime' : function() {
					return function(str) {
						var dt = new Date(Mustache.render(str, this).replace('CEST', 'EST')).toShortString();
						return dt;
					};
				},
				//today, yesterday...
				calendar: function(){
					return function(date) {
						return moment(Mustache.render(date, this).replace('CEST', 'EST')).calendar();
					};
				},
				//0 month 0000
				longDate: function(){
					return function(date) {
						var momentDate = moment(Mustache.render(date, this).replace('CEST', 'EST'));
						if(momentDate !== null){
							return momentDate.format('D MMMM YYYY');
						}
					};
				},
				//0 month
				longDay: function(){
					return function(date) {
						var momentDate = moment(Mustache.render(date, this).replace('CEST', 'EST'));
						if(momentDate !== null){
							return momentDate.format('D MMMM');
						}
					};
				}
			});
			return Mustache.render(this[name] === undefined ? name : this[name], data);
		}
	},
	notify : {
		done : function (msg) { this.instance('success')(msg);},
		error : function (msg) { this.instance('error')(msg); },
		warn : function (msg) {},
		info : function (msg) { this.instance('info')(msg); },
		instance : function(level) {
			return humane.spawn({ addnCls: 'humane-original-' + level });
		}
	},
	i18n : {
		load : function () {
			var that = this;
			$.ajax({url: 'i18n', async: false})
				.done(function(data){
					that.bundle = data;
				})
		},
		bundle : {},
		i18n : function() {
			return function(key) {
				key = Mustache.render(key, this);
				return oneApp.i18n.bundle[key] === undefined ? key : oneApp.i18n.bundle[key];
			};
		},
		translate: function(key){
			return this.i18n()(key);
		}
	},
	message : {
		// TODO : dispatch policiy and paraméter
		css : function(e) {
			if (event.origin == "http://localhost:8008") {
				$("head").append("<link rel='stylesheet' href='" + e.data + "' media='all' />");
			}
		}
	},
	define : function (o) {
		var props = { template : {}, action:{}};
		for (prop in props) {
			for (key in o[prop]) {
				props[[prop]][key] = {'value' : o[[prop]][key]};
			}
			Object.defineProperties(this[prop], props[[prop]]);
		}
	}
};

var oneModule = angular.module('one', ['ngSanitize'], function($interpolateProvider) {
		$interpolateProvider.startSymbol('[[');
		$interpolateProvider.endSymbol(']]');
	})
	.factory('notify', function(){
		if(!window.humane){
			One.load('humane');
		}

		return {
			message: function(type, message){
				message = One.translate(message);
				if(parent !== window){
					messenger.notify(type, message);
				}
				else{
					humane.spawn({ addnCls: 'humane-original-' + type })(message);
				}
			},
			error: function(message){
				this.message('error', message);
			},
			info: function(message){
				this.message('info', message)
			}
		}
	})
	.factory('date', function() {
		if(window.moment === undefined){
			loader.syncLoad('moment');
		}
		var currentLanguage = ( navigator.language || navigator.browserLanguage ).slice( 0, 2 );
		moment.lang(currentLanguage, {
			calendar : {
				lastDay : '[Hier à] HH[h]mm',
				sameDay : '[Aujourd\'hui à] HH[h]mm',
				nextDay : '[Demain à] HH[h]mm',
				lastWeek : 'dddd [dernier à] HH[h]mm',
				nextWeek : 'dddd [prochain à] HH[h]mm',
				sameElse : 'dddd LL'
			}
		});

		return {
			format: function(date, format) {
				if(!moment){
					return '';
				}
				return moment(date).format(format);
			},
			calendar: function(date){
				if(!moment){
					return '';
				}
				return moment(date).calendar();
			}
		};
	})
	.factory('http', function(){
		return {
			get: One.get,
			post: One.post,
			put: One.put,
			delete: One.delete,
			postFile: One.postFile,
			bind: One.bind
		}
	})
	.factory('lang', function(){
		return {
			translate: One.translate
		}
	})
	.factory('_', function(){
		if(window._ === undefined){
			loader.syncLoad('underscore');
		}
		return _;
	})
	.factory('ui', function(){
		return ui;
	});

//directives

oneModule.directive('completeChange', function() {
	return {
		restrict: 'A',
		scope:{
			exec: '&completeChange',
			field: '=ngModel'
		},
		link: function($scope, $linkElement, $attributes) {
			$scope.$watch('field', function(newVal) {
				$linkElement.val(newVal);
			});

			$linkElement.bind('change', function() {
				$scope.field = $linkElement.val();
				$scope.$eval($scope.exec);
			});
		}
	};
});

oneModule.directive('fileInputChange', function($compile){
	return {
		restrict: 'A',
		scope: {
			fileInputChange: '&',
			file: '=ngModel'
		},
		link: function($scope, $element){
			$element.bind('change', function(){
				$scope.file = $element[0].files[0];
				$scope.$apply();
				$scope.fileInputChange();
				$scope.$apply();
			})
		}
	}
})

oneModule.directive('enhancedSelect', function($compile) {
	return {
		restrict: 'E',
		scope:{
			options: '=',
			class: '@',
			current: '=',
			change: '&'
		},
		link: function($scope, $element, $attributes){
			$element.bind('change', function(){
				$scope.current.id = $element.find('.current').data('selected');
				$scope.$eval($scope.change);
			})

		},
		template: '\
			<div>\
				<div class="current fixed cell twelve" data-selected="[[current.id]]">\
					<i role="[[current.icon]]"></i>\
					<span>[[current.text]]</span>\
				</div>\
				<div class="options-list icons-view">\
				<div class="cell three option" data-value="[[option.id]]" data-ng-repeat="option in options">\
					<i role="[[option.icon]]"></i>\
					<span>[[option.text]]</span>\
				</div>\
				</div>\
			</div>'
	};
});

oneModule.directive('translate', function($compile) {
	return {
		restrict: 'A',
		compile: function compile($element, $attributes, transclude) {
			$element.text(lang.translate($attributes.key));
			return {
				pre: function preLink(scope, $element, $attributes, controller) {},
				post: function postLink(scope, $element, $attributes, controller) {}
			};
		}
	};
});

oneModule.directive('translateAttr', function($compile) {
	return {
		restrict: 'A',
		compile: function compile($element, $attributes, transclude) {
			$element.attr($attributes.translateAttr, lang.translate($attributes[$attributes.translateAttr]));
			return {
				pre: function preLink(scope, $element, $attributes, controller) {},
				post: function postLink(scope, $element, $attributes, controller) {}
			};
		}
	};
});

oneModule.directive('preview', function($compile){
	return {
		restrict: 'E',
		template: '<div class="row content-line"><div class="row fixed-block height-four">' +
			'<div class="four cell fixed image clip text-container"></div>' +
			'<div class="eight cell fixed-block left-four paragraph text-container"></div>' +
			'</div></div>',
		replace: true,
		scope: {
			content: '='
		},
		link: function($scope, $element, $attributes){
				$scope.$watch('content', function(newValue){
					var fragment = $(newValue);
					$element.find('.image').html(fragment.find('img').first());

					var paragraph = _.find(fragment.find('p'), function(node){
						return $(node).text().length > 0;
					});
					$element.find('.paragraph').text($(paragraph).text());
				})
			}
		}
})

oneModule.directive('bindHtmlUnsafe', function($compile){
	return {
		restrict: 'A',
		scope: {
			bindHtmlUnsafe: '='
		},
		link: function($scope, $element){
			$scope.$watch('bindHtmlUnsafe', function(newVal){
				$element.html(newVal)
			});
		}
	}
});

oneModule.directive('htmlEditor', function($compile){
	return {
		restrict: 'E',
		transclude: true,
		replace: true,
		scope: {
			ngModel: '='
		},
		template: '<div class="twelve cell"><div contenteditable="true" class="editor-container text-container twelve cell" loading-panel="ckeditor-image">' +
			'</div><div class="clear"></div></div>',
		compile: function($element, $attributes, $transclude){
			CKEDITOR_BASEPATH = '/infra/public/ckeditor/';
			if(window.CKEDITOR === undefined){
				loader.syncLoad('ckeditor');
				CKEDITOR.plugins.basePath = '/infra/public/ckeditor/plugins/';

			}
			return function($scope, $element, $attributes){
				CKEDITOR.fileUploadPath = $scope.$eval($attributes.fileUploadPath);
				CKEDITOR.inlineAll();
				var editor = $('[contenteditable=true]');

				editor.on('focus', function(){
						CKEDITOR.on('instanceReady', function(){
							editor.html($scope.ngModel);
							$('.cke_chrome').width(editor.width());
							$('.cke_chrome').offset({
								top: editor.offset().top - $('.cke_chrome').height(),
								left: editor.offset().left
							});
						})
						$scope.$watch('ngModel', function(newValue){
							if(editor.html() !== newValue){
								editor.html(newValue);
							}
						})
				});

				editor.on('blur', function(e) {
					$scope.ngModel = editor.html();
					$scope.$apply();
				});

				editor.focus();

				$element.on('removed', function(){
					$('.cke').remove();
				})
			}
		}
	}
});

oneModule.directive('loadingPanel', function($compile){
	return {
		restrict: 'A',
		link: function($scope, $element, $attributes){
			http().bind('request-started.' + $attributes.loadingPanel, function(e){
				var loadingIllustrationPath = $('link').attr('href').split('/css')[0] + '/img/illustrations/loading.gif';
				$element.append('<div class="loading-panel">' +
					'<h1>' + lang.translate('loading') + '</h1>' +
					'<img src="' + loadingIllustrationPath + '" />' +
					'</div>');

			})
			http().bind('request-ended.' + $attributes.loadingPanel, function(e){
				$element.find('.loading-panel').remove();
			})
		}
	}
})

$(document).ready(function(){
	angular.bootstrap($('html'), ['one'])
})
